package com.manhwanet.collection.application;

import com.manhwanet.collection.domain.CrawlTaskRepository;
import com.manhwanet.collection.domain.RunRow;
import com.manhwanet.collection.domain.RunStatus;
import com.manhwanet.collection.domain.TaskError;
import com.manhwanet.collection.domain.TaskKind;
import com.manhwanet.collection.domain.TaskRow;
import com.manhwanet.collection.domain.TaskStatus;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.platform.domain.PlatformUrls;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집 작업 큐 (#45, 설계 §7.3·§7.4). 모든 쓰기는 실행 잠금을 쥔 프로세스만 할 수 있다.
 * <ul>
 *   <li>작업은 실행 안에서 (종류, URL 해시)로 한 번만 만든다. 다시 넣으면 기존 작업을 돌려준다.</li>
 *   <li>가져가기(claim)는 SKIP LOCKED 로 같은 작업이 두 번 나가지 않게 한다.
 *       중단 요청 중이고 잠금이 살아 있으면 빈 목록을 돌려준다. 잠금을 잃었으면 409 이다.</li>
 *   <li>일시적 오류(타임아웃·연결·5xx·429)는 정한 시각 뒤에 다시 시도하고, 그 밖의 오류는 FAILED(robots·성인 건너뜀은 SKIPPED)로 닫는다.
 *       마지막 HTTP 상태·오류·시도 횟수를 남긴다.</li>
 *   <li>파싱 실패 원문은 실행당 최대 200건까지 gzip 으로 보관한다(30일 뒤 정리 대상).</li>
 * </ul>
 * URL 은 형식만 검사하고 이 서비스는 요청을 보내지 않는다(HTTP 수집은 #46).
 */
@Service
@Transactional(readOnly = true)
public class CrawlTaskService {

    public static final int MAX_CLAIM = 100;
    public static final int MAX_ATTEMPTS = 5;
    public static final int MAX_RETRY_DELAY_SECONDS = 86_400;
    public static final int MAX_SAMPLES_PER_RUN = 200;
    public static final int MAX_SAMPLE_BYTES = 1_048_576;
    public static final int MAX_TASK_URL_LENGTH = 1000;
    static final Set<TaskError> RETRYABLE = EnumSet.of(TaskError.TIMEOUT, TaskError.CONNECTION, TaskError.HTTP_5XX,
            TaskError.RATE_LIMITED);
    static final Set<TaskError> SKIPPING = EnumSet.of(TaskError.SKIPPED_ROBOTS, TaskError.SKIPPED_ADULT);

    private final CrawlTaskRepository taskRepository;
    private final CrawlRunService runService;

    public CrawlTaskService(CrawlTaskRepository taskRepository, CrawlRunService runService) {
        this.taskRepository = taskRepository;
        this.runService = runService;
    }

    public record Enqueued(TaskRow task, boolean created) {
    }

    @Transactional
    public Enqueued enqueue(long runId, String owner, TaskKind kind, String rawUrl) {
        if (kind == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "작업 종류가 필요합니다.");
        }
        String url = taskUrl(rawUrl);
        RunRow run = runService.requireLeaseForWrite(runId, owner, true);
        if (run.status() == RunStatus.STOP_REQUESTED) {
            throw new BusinessException(ErrorCode.CRAWL_RUN_STATE_CONFLICT, "중단 요청 중에는 새 작업을 넣지 않습니다.");
        }
        String hash = sha256(url);
        boolean created = taskRepository.insertIfAbsent(runId, kind, url, hash).isPresent();
        return new Enqueued(taskRepository.find(runId, kind, hash).orElseThrow(), created);
    }

    @Transactional
    public List<TaskRow> claim(long runId, String owner, int limit) {
        if (limit < 1 || limit > MAX_CLAIM) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "limit 은 1~" + MAX_CLAIM + " 사이여야 합니다.");
        }
        RunRow run = runService.requireLeaseForWrite(runId, owner, true);
        if (run.status() == RunStatus.STOP_REQUESTED) {
            return List.of();
        }
        return taskRepository.claim(runId, limit);
    }

    @Transactional
    public TaskRow succeed(long taskId, String owner, Integer httpStatus) {
        TaskRow task = runningTask(taskId, owner);
        taskRepository.finish(task.id(), TaskStatus.DONE, http(httpStatus), null);
        return taskRepository.findById(taskId).orElseThrow();
    }

    /**
     * 실패 기록. retryAfterSeconds 가 있고 다시 시도할 수 있는 오류이며 시도 횟수가 남아 있으면 PENDING 으로 되돌린다.
     * failedBody 는 PARSE_FAILED 일 때만 표본으로 남긴다.
     */
    @Transactional
    public TaskRow fail(long taskId, String owner, TaskError error, Integer httpStatus, Integer retryAfterSeconds,
            byte[] failedBody) {
        if (error == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "실패 사유가 필요합니다.");
        }
        if (retryAfterSeconds != null && (retryAfterSeconds < 0 || retryAfterSeconds > MAX_RETRY_DELAY_SECONDS)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "retryAfterSeconds 는 0~" + MAX_RETRY_DELAY_SECONDS + " 사이여야 합니다.");
        }
        TaskRow task = runningTask(taskId, owner);
        Integer status = http(httpStatus);
        if (RETRYABLE.contains(error) && retryAfterSeconds != null && task.attempts() < MAX_ATTEMPTS) {
            taskRepository.retryLater(task.id(), retryAfterSeconds, status, error);
        } else {
            taskRepository.finish(task.id(), SKIPPING.contains(error) ? TaskStatus.SKIPPED : TaskStatus.FAILED, status, error);
        }
        if (error == TaskError.PARSE_FAILED && failedBody != null
                && taskRepository.countSamples(task.runId()) < MAX_SAMPLES_PER_RUN) {
            byte[] gzip = gzip(failedBody);
            if (gzip.length <= MAX_SAMPLE_BYTES) {
                taskRepository.insertSample(task.id(), status, error, gzip);
            }
        }
        return taskRepository.findById(taskId).orElseThrow();
    }

    private TaskRow runningTask(long taskId, String owner) {
        TaskRow task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CRAWL_TASK_NOT_FOUND));
        // 진행 중인 요청은 중단 요청 뒤에도 끝낼 수 있다
        runService.requireLeaseForWrite(task.runId(), owner, true);
        TaskRow locked = taskRepository.lockById(taskId).orElseThrow();
        if (locked.status() != TaskStatus.RUNNING) {
            throw new BusinessException(ErrorCode.CRAWL_RUN_STATE_CONFLICT, "진행 중인 작업이 아닙니다(" + locked.status() + ").");
        }
        return locked;
    }

    static String taskUrl(String raw) {
        if (raw == null || raw.isBlank() || raw.strip().length() > MAX_TASK_URL_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "작업 URL 은 1~" + MAX_TASK_URL_LENGTH + "자여야 합니다.");
        }
        String value = raw.strip();
        // 작업 URL 은 길 수 있어 공식 URL 보다 긴 상한을 쓰되, 같은 정규화·검사를 앞 500자 규칙과 별도로 적용한다
        PlatformUrls.Result result = value.length() <= PlatformUrls.URL_MAX_LENGTH
                ? PlatformUrls.validateGeneral(value)
                : null;
        if (result != null && !result.ok()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "작업 URL: " + result.reason());
        }
        if (result == null && (!value.startsWith("https://") || value.codePoints().anyMatch(cp -> cp <= 0x20 || cp >= 0x7f))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "작업 URL 은 공백·비 ASCII 없는 https 주소여야 합니다.");
        }
        return result == null ? value : result.normalizedUrl();
    }

    private static Integer http(Integer status) {
        if (status != null && (status < 100 || status > 599)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "HTTP 상태는 100~599 여야 합니다.");
        }
        return status;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] gzip(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
