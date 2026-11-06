#ifndef SB_PORTS_H
#define SB_PORTS_H
#include "sb_types.h"
/* Thread context only: short guards never span I/O, delay or a blocking call. */
int32_t sb_platform_lock(void);
void sb_platform_unlock(int32_t prior);
uint32_t sb_platform_tick(void);
uint32_t sb_platform_frequency(void);
SbResult sb_ms_to_ticks(uint64_t ms, uint32_t frequency, uint32_t *out);
SbResult sb_clock_init(void);
SbResult sb_clock_get(SbClock *out);
SbResult sb_sensor_init(void);
SbResult sb_sensor_read(SbInputKind kind, SbSensorFrame *out);
SbResult sb_output_init(void);
SbResult sb_output_set(const SbOutputRequest *request);
SbResult sb_output_get(SbOutputKind kind, SbOutputState *out);
SbResult sb_output_poll(void);
void sb_pipeline_init(void);
SbResult sb_pipeline_process(const SbSensorFrame *frame);
SbResult sb_pipeline_snapshot(SbSnapshot *out);
SbResult sb_event_buffer_init(void);
SbResult sb_event_buffer_put(const SbEventCandidate *item);
SbResult sb_event_buffer_get(SbEventCandidate *out);
uint64_t sb_event_buffer_rejected(void);
SbResult sb_network_poll(void);
SbResult sb_network_publish(const SbEventCandidate *item);
/* Advance to the first deadline strictly after now; no burst catch-up. */
SbResult sb_schedule_advance(uint64_t now,uint32_t period,uint64_t *deadline,uint64_t *missed);
SbResult sb_app_start(void);
void sb_app_status(SbAppStatus *out);
#endif
