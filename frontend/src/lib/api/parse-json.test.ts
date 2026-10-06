import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { parseApiBody } from "./parse-json";
import { workDetailHref } from "../works/params";

const SMALL_ID = "21";
const MAX_SAFE = "9007199254740991";
const TWO_TO_53 = "9007199254740992";
const PAST_SAFE = "9007199254740993";
const LONG_MAX = "9223372036854775807";

const IDS = [SMALL_ID, MAX_SAFE, TWO_TO_53, PAST_SAFE, LONG_MAX];

type WorkPayload = {
  id: string;
  creators: { creatorId: string; name: string; role: string }[];
};

type PagePayload = {
  content: WorkPayload[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
};

function envelope(data: string): string {
  return `{"success":true,"data":${data},"error":null}`;
}

function workJson(id: string, creatorId: string): string {
  return `{"id":${id},"workFormat":"PAGE_COMIC","originCountry":"KR","primaryTitle":{"title":"작품","languageCode":"ko"},"creators":[{"creatorId":${creatorId},"name":"작가","role":"STORY"}],"createdAt":"2026-01-01T00:00:00Z"}`;
}

describe("parseApiBody", () => {
  for (const id of IDS) {
    it(`keeps work id ${id} and builds the same detail link`, () => {
      const parsed = parseApiBody(envelope(workJson(id, SMALL_ID))) as {
        data: WorkPayload;
      };

      assert.equal(parsed.data.id, id);
      assert.equal(typeof parsed.data.id, "string");
      assert.equal(workDetailHref(parsed.data.id, 1), `/works/${id}`);
      assert.equal(workDetailHref(parsed.data.id, 2), `/works/${id}?from=2`);
    });
  }

  it("keeps creator ids that JSON.parse rounds", () => {
    const parsed = parseApiBody(envelope(workJson(SMALL_ID, LONG_MAX))) as {
      data: WorkPayload;
    };
    const rounded = JSON.parse(workJson(SMALL_ID, LONG_MAX)) as {
      creators: { creatorId: number }[];
    };

    assert.equal(parsed.data.creators[0]?.creatorId, LONG_MAX);
    assert.equal(typeof parsed.data.creators[0]?.creatorId, "string");
    assert.notEqual(String(rounded.creators[0]?.creatorId), LONG_MAX);
    assert.notEqual(String(JSON.parse(workJson(PAST_SAFE, PAST_SAFE)).id), PAST_SAFE);
    assert.notEqual(String(JSON.parse(workJson(LONG_MAX, LONG_MAX)).id), LONG_MAX);
  });

  it("leaves page counts as numbers", () => {
    const page = `{"content":[${workJson(LONG_MAX, PAST_SAFE)}],"page":1,"size":20,"totalElements":21,"totalPages":2,"hasNext":true}`;
    const parsed = parseApiBody(envelope(page)) as { data: PagePayload };
    const item = parsed.data.content[0];

    assert.equal(item?.id, LONG_MAX);
    assert.equal(item?.creators[0]?.creatorId, PAST_SAFE);
    assert.equal(workDetailHref(item?.id ?? "", 2), `/works/${LONG_MAX}?from=2`);
    assert.equal(parsed.data.page, 1);
    assert.equal(parsed.data.size, 20);
    assert.equal(parsed.data.totalElements, 21);
    assert.equal(parsed.data.totalPages, 2);
    assert.equal(parsed.data.hasNext, true);
    assert.equal(typeof parsed.data.page, "number");
    assert.equal(typeof parsed.data.size, "number");
    assert.equal(typeof parsed.data.totalElements, "number");
    assert.equal(typeof parsed.data.totalPages, "number");
    assert.equal(typeof parsed.data.hasNext, "boolean");
  });
});
