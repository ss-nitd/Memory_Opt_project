# DroidKaigi Presentation Feedback

## Surviving the Low Memory Killer with Local AI Runtimes

The presentation has a strong premise and a genuinely compelling demo. Its main
weakness is that it currently feels like several talks stitched together: LMKD,
memory allocation, `mmap`, hardware selection, thermals, and WAL recovery. Some
of the strongest numerical claims also come from the older synthetic workload
rather than the real MiniLM semantic-search demo.

The new 12-engine semantic-search demo should become the spine of the talk.

## Recommended central message

Repeat this thesis throughout the presentation:

> `mmap` removes duplicate model backing. `SharedMemory` relieves Java-heap
> pressure. Neither eliminates LiteRT tensor arenas or total process memory.
> Surviving LMKD requires measurement, workload shedding, and recoverable state.

This is technically honest, memorable, and directly supported by the demo.

## Highest-priority changes

### 1. Replace the synthetic `mmap` headline with the real demo

The current claims that Java heap fell from 125.2 MiB to about 52 MiB and the
physical working set fell from 72 MiB to 6 MiB came from the synthetic 6 MiB per
engine harness. They should not be presented as results from the real MiniLM
workload.

Use the actual semantic-search result instead:

| Real semantic-search demo | Result |
|---|---:|
| MiniLM interpreters | 12 |
| Threads per interpreter | 1 |
| Model size | 21.7 MiB |
| Total virtual model mappings | 260.9 MiB |
| Unique file backing | 21.7 MiB |
| Potential duplicate model backing avoided | 239.2 MiB |
| Warm Java heap | About 16.4 MiB |
| Warm App PSS | About 287 MiB |
| Shared tensor I/O | 30 KiB |
| Document | 1,060 words / 24 chunks |
| Tokens processed | 1,417 |
| Embedding dimensions | 384 |
| Cold end-to-end search | 337.5 ms |
| First-run pool initialization | 88.1 ms |
| Warm search | 184.7 ms |
| Semantic result | Correct passage, similarity 0.563 |

The powerful part is the contrast:

> The model pages are shared, but twelve LiteRT execution arenas still cost
> memory.

This explains why PSS remains around 287 MiB and preempts questions about why
the process still uses substantial memory after enabling `mmap`.

### 2. Clearly separate the different memory measurements

Several slides currently blur virtual memory, Java heap, and physical RAM. Add
one compact explanatory slide:

| Metric | What it tells you |
|---|---|
| Java heap | Memory managed by ART and subject to GC and heap limits |
| Virtual mapped capacity | Address space covered by mappings; not necessarily resident |
| App PSS | The process's proportional share of resident physical memory |
| RSS | All resident mappings visible to the process, including shared mappings |
| Native heap | Allocations attributed to native allocators; not all mapped memory |

Do not describe 260.9 MiB of virtual mappings as 260.9 MiB of physical RAM.
Likewise, do not describe a 21.7 MiB file as the measured physical working set
without page-residency measurements.

### 3. Reframe `SharedMemory` as heap relief, not RAM savings

The controlled tensor result is an excellent teaching moment if it is clearly
labeled as a stress test:

| Controlled tensor stress test | Heap tensors | `SharedMemory` |
|---|---:|---:|
| Tensor capacity | 48 MiB | 48 MiB |
| Java heap | 125.2 MiB | 77.2 MiB |
| App PSS | 181.7 MiB | 181.9 MiB |

The insight should be:

> 48 MiB left the Java heap, but it did not disappear from RAM.

Label this slide **Controlled tensor stress test**. Follow it with the real
semantic-search result: the real external tensor I/O is only 30 KiB across the
12 engines. `SharedMemory` is valid in the implementation, but it is not the
source of a dramatic total-memory improvement for this particular model.

Rename **Native Memory Space** to **Shared mapped pages**. `SharedMemory` is a
mapped region, not necessarily part of the native allocator heap. Every Android
`SharedMemory.map*()` call should be paired with `SharedMemory.unmap()`, and the
file-descriptor handle should be closed when the region is no longer needed.

Reference: [Android `SharedMemory` documentation](https://developer.android.com/reference/android/os/SharedMemory)

### 4. Rewrite the trim-memory section

This is the most important factual correction in the deck.

`TRIM_MEMORY_RUNNING_LOW`, `TRIM_MEMORY_RUNNING_MODERATE`, and
`TRIM_MEMORY_RUNNING_CRITICAL` are deprecated in API 35. Apps have not been
notified of those levels since Android 14/API 34. Android's current guidance says
that these callbacks were not useful for preventing low-memory kills.
`TRIM_MEMORY_UI_HIDDEN` and `TRIM_MEMORY_BACKGROUND` remain useful.

References:

- [`ComponentCallbacks2` API reference](https://developer.android.com/reference/android/content/ComponentCallbacks2.html)
- [Android low-memory-killer guidance](https://developer.android.com/topic/performance/vitals/lmk)

Remove or rewrite these statements:

- "Intercepting the Signals"
- "lmkd is actively hunting"
- "You are next on the hit list"
- Code built around `TRIM_MEMORY_RUNNING_LOW` and
  `TRIM_MEMORY_RUNNING_CRITICAL`

Trim callbacks are best-effort lifecycle and background-state hints. They are
not a guaranteed countdown before LMKD.

Replace the implementation slide with something similar to:

```kotlin
override fun onTrimMemory(level: Int) {
    when {
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
            releaseRebuildableRuntimeResources()

        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
            releaseUiBoundResources()
    }
}
```

Then explain the modern strategy:

1. Establish proactive budgets using device RAM and measured runtime costs.
2. Stop opening interpreters before exceeding the budget.
3. Release rebuildable resources when the app is backgrounded.
4. Persist checkpoints before long-running work.
5. On the next launch, inspect `ApplicationExitInfo.REASON_LOW_MEMORY`.

`ApplicationExitInfo` can report that the previous process died because of low
memory and can expose the last sampled PSS and RSS values.

Reference: [`ApplicationExitInfo` API reference](https://developer.android.com/reference/android/app/ApplicationExitInfo)

### 5. Correct the LMKD explanation

Change `oom_adj_score` to `/proc/<pid>/oom_score_adj`.

Avoid showing fixed kill-score ranges as universal rules. Process importance
matters, but modern LMKD can also consider PSI memory-pressure signals,
thrashing, swap utilization, and device configuration. At critical pressure,
even a score of zero can be eligible.

Suggested wording:

> LMKD combines system memory pressure with process importance. Cached
> processes normally go first, but foreground status is not absolute immunity
> under critical pressure.

Replace:

> Out of ZRAM. LMKD takes over.

with:

> Sustained pressure, thrashing, and swap conditions can cause LMKD to reclaim
> processes.

Exhausting ZRAM is not a mandatory stage before LMKD acts.

Reference: [AOSP LMKD documentation](https://source.android.com/docs/core/perf/lmkd)

## `mmap` slide corrections

Replace absolute claims with precise ones:

| Current wording | Recommended wording |
|---|---|
| Zero-copy | Avoids an explicit userspace model copy |
| Instant start | Lower initialization cost; pages fault in when accessed |
| Physical working set -92% | Twelve mappings reference one file backing |
| No scanning | Model bytes are outside the managed Java heap |
| Every mmap needs munmap | Distinguish native `mmap` from Java `MappedByteBuffer` |

`mmap` does not eliminate:

- Page faults or storage I/O.
- LiteRT interpreter state.
- Native tensor arenas.
- Delegate-specific weight transformations or caches.
- The need to measure total App PSS.

### Correct Java mapping lifecycle

For Java `FileChannel.map()`, closing the channel does not invalidate or unmap
the returned buffer. A `MappedByteBuffer` and its mapping remain valid until the
buffer is garbage-collected; Java does not provide a standard public,
deterministic `unmap()` operation.

References:

- [Java `FileChannel` documentation](https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/nio/channels/FileChannel.html)
- [Java `MappedByteBuffer` documentation](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/nio/MappedByteBuffer.html)

The real lifecycle lesson from the implementation is:

> Retain the mapped buffer for the interpreter's lifetime. Close the
> interpreter, release the buffer reference, and never assume that closing the
> file channel invalidates the mapping.

For Android `SharedMemory`, explicit `SharedMemory.unmap(buffer)` is supported.
These are related mechanisms with different public lifecycles.

## Align the opening with the actual demo

The opening focuses heavily on autoregressive generation and KV caches, while
the demo uses an embedding model. This creates a narrative mismatch.

Broaden the memory model:

```text
Local AI memory
= model weights
+ runtime/interpreter state
+ tensor arenas
+ dynamic workload state
```

Then explain:

- Generative models grow KV caches as the context grows.
- Embedding workloads grow through concurrent interpreters and tensor arenas.
- The demo uses parallel semantic search because it makes memory sharing and
  concurrency visible, useful, and repeatable.

This keeps the KV-cache explanation without implying that the demo is an
autoregressive LLM.

## Make the demo the midpoint payoff

### Suggested live sequence

1. Show the 1,060-word document and the natural-language question.
2. Explain that the document becomes 24 independent passages.
3. State that the app owns 12 real MiniLM interpreters with one thread each.
4. Run the optimized search.
5. Show the correct passage rather than beginning with a metrics screen.
6. Call out `12/12 engines`, `24/24 chunks`, and the measured warm latency.
7. Show `260.9 MiB virtual / 21.7 MiB unique model file`.
8. Switch to the Memory tab and show Java heap, App PSS, and native memory.
9. Explain why PSS remains high: every interpreter still owns execution state
   and tensor arenas.

A useful spoken line is:

> `mmap` solved duplicated weights. It did not solve the twelve interpreters,
> and that distinction is the entire point.

### Do not intentionally OOM the live demo

The Pixel 6 emulator reports a 192 MiB Java heap growth limit, while twelve
copied 21.7 MiB model buffers represent 260.9 MiB before interpreter arenas.
Attempting the complete copied-model baseline can fail before the 12-engine pool
is ready.

Prefer a safe preflight screen:

```text
12 copied models require: 260.9 MiB
Current Java heap growth limit: 192 MiB
Result: 12-engine pool initialization blocked
Recommendation: enable file-backed model mapping
```

This is safer and more persuasive than crashing the process on stage.

Keep a short screen recording of the successful demo as a fallback for emulator,
ADB, or projector problems.

## Demo numbers suitable for the deck

### Test configuration

| Parameter | Value |
|---|---:|
| Test environment | Pixel 6 Android 16 emulator |
| MiniLM interpreters | 12 |
| Threads per interpreter | 1 |
| Document size | 1,060 words |
| Search chunks | 24 |
| Chunks per active engine | 2 |
| Tokens processed | 1,417 |
| Embedding dimensions | 384 |
| Model loading | Read-only `mmap` |
| External tensor I/O | Android `SharedMemory` |

### Performance

| Metric | Cold run | Warm run | Difference |
|---|---:|---:|---:|
| End-to-end search | 337.5 ms | 184.7 ms | 45.3% lower |
| Pool initialization | 88.1 ms | 0 ms | Pool reused |
| Search excluding initialization | 249.4 ms | 184.7 ms | 25.9% lower |
| Chunk throughput | 71.1 chunks/s | 129.9 chunks/s | 1.83x higher |
| Token throughput | About 4,198 tokens/s | About 7,672 tokens/s | 1.83x higher |
| Engines used | 12/12 | 12/12 | Full pool used |
| Cosine similarity | 0.563 | 0.563 | Equivalent result |

### Memory snapshots

| Metric | Before search | Cold pool | Warm/settled pool |
|---|---:|---:|---:|
| Java heap | 7.7 MiB | 14.7 MiB | 16.4 MiB |
| Native heap | Not captured | 222.3 MiB | 209.4 MiB |
| Total App PSS | 64.6 MiB | 296.6 MiB | 287.0 MiB |
| Total RSS | Not captured | 562.1 MiB | 554.9 MiB |
| Virtual model mappings | 0 MiB | 260.9 MiB | 260.9 MiB |
| Unique model file capacity | 0 MiB | 21.7 MiB | 21.7 MiB |
| Shared tensor I/O | 0 KiB | 30 KiB | 30 KiB |

These values are useful for explaining the demo, but they are single emulator
observations rather than publication-quality benchmark distributions. Label them
accordingly.

## Add the benchmark the presentation currently lacks

Cold versus warm demonstrates initialization reuse, but it does not demonstrate
that twelve engines are the optimal concurrency level.

Collect this matrix on a physical Pixel 6:

| Engines | Warm latency | Chunks/s | Java heap | App PSS | Thermal status |
|---:|---:|---:|---:|---:|---|
| 1 | TBD | TBD | TBD | TBD | TBD |
| 2 | TBD | TBD | TBD | TBD | TBD |
| 4 | TBD | TBD | TBD | TBD | TBD |
| 8 | TBD | TBD | TBD | TBD | TBD |
| 12 | TBD | TBD | TBD | TBD | TBD |

This is likely to become the talk's most useful chart. Throughput may increase
and then plateau while native memory continues to grow. That supports an
important production conclusion:

> Maximum concurrency is not optimal concurrency.

### Benchmark methodology

- Use a physical device for final performance and thermal claims.
- Use a release build.
- Keep the document, query, model, and tokenizer identical.
- Use cooldown periods between configurations.
- Record current thermal status and headroom.
- Run enough repetitions to report median and p95 rather than a single run.
- Separate cold initialization from warm inference.
- Report Java heap, PSS, and virtual mappings independently.
- Record model correctness as well as performance.

The emulator is suitable for functional validation and API plumbing. It should
not be presented as evidence of production thermal behavior or Pixel 6 hardware
performance.

## Hardware-selection corrections

Remove or soften the following claims:

- "CPU: Sequential"
- "GPU: Steals UI rendering cycles"
- "NPU: Lightning fast. Zero thermal hit"
- "Bypasses deprecated wrappers for direct hardware execution"

CPUs are parallel, GPU contention is workload and device dependent, and NPUs
still consume power and produce heat.

Use this framing:

| Hardware | More accurate characterization |
|---|---|
| CPU | Broad compatibility; often higher energy per inference |
| GPU | High parallel throughput; delegate support and UI contention vary |
| NPU | Potentially better performance per watt; model and device support vary |

LiteRT NPU support is vendor-specific rather than a universally portable switch.
The current official guidance describes vendor delegates and device-dependent
availability.

Reference: [LiteRT NPU delegate documentation](https://ai.google.dev/edge/litert/android/npu)

The current demo uses LiteRT interpreters with the CPU/XNNPACK path. Say that
explicitly. Present `CompiledModel` and accelerator targeting as the next
production step, not as something the current demo proves.

## Thermal-slide corrections

For `PowerManager.getThermalHeadroom()`:

- `1.0` represents the severe-throttling threshold.
- Values can exceed `1.0`.
- Frequent calls can return `NaN`.
- A forecast takes multiple samples before it becomes meaningful.
- The same value can correspond to different thermal behavior across devices.

Reference: [`PowerManager.getThermalHeadroom()` documentation](https://developer.android.com/reference/kotlin/android/os/PowerManager)

The current 24-chunk search lasts only about 185 ms when warm and is not a
sustained thermal workload. To demonstrate mitigation, repeat a fixed workload
for several minutes on a physical device, plot latency and headroom over time,
and show the point where workload shedding occurs.

The emulator can validate that the Thermal API is called and displayed. It
cannot validate real throttling behavior.

## SIGKILL and recovery corrections

The contrast between a Java `OutOfMemoryError` and LMKD termination is useful,
but "zero logs, zero traces" is too absolute.

Use this distinction:

| Failure | What the process can do |
|---|---|
| Java `OutOfMemoryError` | May execute application code, although recovery is risky |
| Native crash | May produce a tombstone or crash report |
| LMKD/SIGKILL | Cannot run cleanup or persist new state at termination time |
| Next launch | Can inspect `ApplicationExitInfo` for the prior exit reason |

The production rule remains:

> Never depend on finalizers, shutdown hooks, or last-second persistence when
> correctness matters.

The WAL/checkpoint section is valuable if it is directly tied to this rule.
Otherwise it feels like the beginning of a separate persistence talk. In a
20-minute session, compress it to a single recovery slide.

## Recommended presentation flow

Assuming a 20-25 minute session:

1. **Title and failure story:** A local-AI feature works until the process
   disappears without a Java crash.

2. **What local-AI memory contains:** Weights, runtime state, tensor arenas, and
   dynamic workload state.

3. **How LMKD actually chooses victims:** Pressure, process importance, and why
   foreground is not absolute immunity.

4. **Why SIGKILL is different:** No cleanup at death; detect and recover on the
   next launch.

5. **Demo workload and measurement methodology:** 1,060 words, 24 chunks, and 12
   real MiniLM interpreters.

6. **Why copied models do not fit:** 12 x 21.7 MiB exceeds the emulator's 192 MiB
   Java heap growth limit.

7. **`mmap`: one file backing, many interpreters:** Explain mappings, page
   faults, and what is actually shared.

8. **Live semantic-search demo:** Show the useful result before the metrics.

9. **Measured result and remaining native cost:** 21.7 MiB file backing, 260.9
   MiB mappings, and about 287 MiB PSS.

10. **`SharedMemory`: heap relief versus total RAM:** Contrast the controlled 48
    MiB stress test with the real 30 KiB I/O.

11. **Modern memory-response strategy:** Proactive budgets, background release,
    and no reliance on deprecated running trim callbacks.

12. **Thermal and accelerator constraints:** Hardware support and physical-device
    measurement.

13. **Recovery after termination:** Checkpoints, idempotent resume, and
    `ApplicationExitInfo`.

14. **Production checklist:** A short, concrete list rather than another
    architecture diagram.

15. **Single-sentence conclusion:** End on the production principle below.

## Suggested production checklist slide

- Map immutable model files rather than copying them per interpreter.
- Retain model buffers for the complete interpreter lifetime.
- Measure Java heap, native heap, PSS, and mapped capacity separately.
- Reuse buffers, but do not claim that moving them off heap saves total RAM.
- Size interpreter pools from measured throughput and memory, not a round number.
- Stop admitting new work before reaching the process budget.
- Release rebuildable state when the app becomes hidden or backgrounded.
- Measure sustained thermal behavior on real devices.
- Persist resumable progress before long-running work starts.
- Inspect prior low-memory exits on the next launch.

## Presentation and visual polish

- Fix visible typos and broken words: `warning`, `UI_HIDDEN`, and `Let's`.
- Remove the duplicated `RUNNING_LOW` and `RUNNING_CRITICAL` labels.
- Use fewer emojis; they currently compete with the technical diagrams.
- Keep one main claim and one main number per slide.
- Avoid paragraph-sized table cells that cannot be read from the back of a room.
- Label synthetic results as **Controlled stress test**.
- Label current demo data as **Pixel 6 emulator - single observed run**.
- Add small source footers to slides with Android platform claims.
- Add a repository QR code beside the demo methodology.
- Keep a prerecorded demo result available as a stage fallback.

Use consistent memory colors throughout:

| Color | Meaning |
|---|---|
| Purple | Java/ART managed heap |
| Blue | File-backed or shared mappings |
| Orange | Native LiteRT state and tensor arenas |
| Red | Memory pressure and process termination |

## Suggested demo headline

> Twelve MiniLM engines searched 1,060 words in 184.7 ms while sharing one
> 21.7 MiB `mmap`-backed model file.

Immediately follow it with the caveat:

> The process still used about 287 MiB of PSS because every interpreter retained
> native execution state. Shared weights are only one part of the memory budget.

## Suggested closing

> Share static weights. Budget native execution state. Shed work before pressure
> becomes fatal. Persist enough state to recover when the process still dies.

That conclusion connects the memory, runtime, thermal, and recovery sections
without overstating what any single optimization achieves.
