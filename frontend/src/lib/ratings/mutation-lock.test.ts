import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { createRatingMutationLock } from "./mutation-lock";

async function flush(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

describe("rating mutation lock", () => {
  it("sends only the first change when save and delete start in the same turn", async () => {
    const lock = createRatingMutationLock();
    const sent: string[] = [];

    async function save(): Promise<void> {
      await lock.run(async () => {
        sent.push("PUT");
        await flush();
      });
    }
    async function remove(): Promise<void> {
      await lock.run(async () => {
        sent.push("DELETE");
        await flush();
      });
    }

    const pending = Promise.all([save(), remove()]);
    await pending;
    assert.deepEqual(sent, ["PUT"]);
  });

  it("sends only the delete when delete starts before save in the same turn", async () => {
    const lock = createRatingMutationLock();
    const sent: string[] = [];

    async function save(): Promise<void> {
      await lock.run(async () => {
        sent.push("PUT");
        await flush();
      });
    }
    async function remove(): Promise<void> {
      await lock.run(async () => {
        sent.push("DELETE");
        await flush();
      });
    }

    await Promise.all([remove(), save()]);
    assert.deepEqual(sent, ["DELETE"]);
  });

  it("sends one request when save is invoked twice in the same turn", async () => {
    const lock = createRatingMutationLock();
    let puts = 0;

    async function save(): Promise<void> {
      await lock.run(async () => {
        puts += 1;
        await flush();
      });
    }

    await Promise.all([save(), save()]);
    assert.equal(puts, 1);
  });

  it("runs the next change after the first one, including its refetch, has finished", async () => {
    const lock = createRatingMutationLock();
    const sent: string[] = [];

    async function save(label: string): Promise<void> {
      await lock.run(async () => {
        sent.push(label);
        await flush();
      });
    }

    await save("first");
    await save("second");
    assert.deepEqual(sent, ["first", "second"]);
  });

  it("releases the lock when the change throws", async () => {
    const lock = createRatingMutationLock();
    await assert.rejects(
      lock.run(async () => {
        throw new Error("failed");
      }),
      /failed/,
    );

    let ran = false;
    await lock.run(async () => {
      ran = true;
    });
    assert.equal(ran, true);
  });
});
