#ifndef SB_RULES_H
#define SB_RULES_H
#include "sb_ports.h"

typedef struct {
    bool pose_valid;
    bool calibrated;
    bool calibrating;
    float tilt_deg;
    uint8_t pose_state; /* 0 NORMAL, 1 TILTED, 2 SIDEWAYS, 3 INVERTED */
    SbDoorState door;
    bool sentry_armed;
    bool sentry_arming;
    uint32_t sentry_revision;
    SbGpsMode gps_mode;
    char profile_id[49];
    uint64_t fast_detected_ms,fast_output_ms;
    uint64_t event_candidates;
    uint64_t suppressed_environment_events;
} SbRulesStatus;

void sb_rules_init(void);
SbResult sb_rules_process(const SbSensorFrame *frame);
SbResult sb_rules_poll(uint64_t now_ms);
SbResult sb_rules_apply_profile(float temp_low, float temp_high,
                                float humidity_low, float humidity_high,
                                float temp_hysteresis, float humidity_hysteresis,
                                const char *profile_id);
SbResult sb_rules_start_calibration(void);
SbResult sb_rules_set_gps_mode(SbGpsMode mode,uint32_t seed);
SbResult sb_rules_set_sentry(uint32_t revision, bool arm, uint32_t duration_s);
SbResult sb_rules_box_state(SbDoorState door, bool historical,
                            const char *conclusion_id);
SbResult sb_rules_set_geofence(double latitude, double longitude, double radius_m);
SbResult sb_rules_status(SbRulesStatus *out);

#endif
