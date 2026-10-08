/**
 * 별점 저장·삭제가 같은 화면 갱신 안에서 겹치지 않게 하는 잠금.
 * 구현은 공통 잠금(../mutation-lock)과 같다. 리뷰 화면도 같은 잠금을 쓴다.
 */
import { type MutationLock, createMutationLock } from "../mutation-lock";

export const createRatingMutationLock = createMutationLock;

export type RatingMutationLock = MutationLock;
