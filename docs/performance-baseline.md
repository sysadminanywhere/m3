# Preliminary directory-flow measurement

This is a single exploratory run, not a capacity guarantee or a comparison between releases. The workload is small and does not represent every production installation.

## Setup

On 2026-10-27, a clean PostgreSQL 17 database, RabbitMQ 4, one M3 Community web process and one worker ran in separate Docker containers on Docker Desktop for Windows. Docker reported a 15.05 GiB memory limit. The Community application version was 1.0.1, built from the working tree. The worker used `M3_WORKER_POLL_DELAY_MS=50`; the enabled DIRECTORY-to-DIRECTORY rule used a 100 ms source polling interval and 1 ms minimum file age. Retention was disabled and source-file deletion was off. Input and output directories were bind mounts on the host. The receiver was reporting `RUNNING` before the run began.

One burst of 100 distinct UTF-8 text files was written using temporary filenames followed by rename. The files totalled 25,090 bytes. Wall time starts before writing the first file and ends when the database reports 100 outbound records as `SENT`. The job queue was sampled about once per second; its reported maximum is a lower bound. Database size was measured before writing and after completion using `pg_database_size`, which is affected by PostgreSQL allocation and is not per-message disk cost.

## Observations

| Measure | Result |
| --- | ---: |
| Complete burst | 15.02 s, approximately 6.66 delivered files/s |
| Sampled pending or processing jobs, maximum | 70 |
| Inbound database creation to outbound `processed_at`, mean / p50 / p95 / max | 5.288 / 5.422 / 7.985 / 8.218 s |
| Database size change | 2,572,288 bytes |
| Inbound files / outbound files | 100 / 100 |
| Failed / pending / completed jobs after delivery | 0 / 0 / 200 |
| Output file SHA-256 mismatches | 0 |

The latency excludes file creation and time before the inbound message is committed; wall time includes file creation and polling. The numbers include one worker, the selected polling settings, local Docker and the test database, so they must not be generalized to Kafka, remote servers, larger messages, sustained traffic, cold storage, or another machine. No CPU or memory peak was measured. Repeat runs after warm-up, add larger payloads and sustained workloads, and record resource peaks before drawing scaling conclusions.
