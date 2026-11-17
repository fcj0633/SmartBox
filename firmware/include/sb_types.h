#ifndef SB_TYPES_H
#define SB_TYPES_H
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
typedef enum { SB_OK, SB_INVALID_ARG, SB_UNSUPPORTED_CAP, SB_NO_DATA,
    SB_FULL, SB_NOT_IMPLEMENTED, SB_PLATFORM_ERROR } SbResult;
typedef enum { SB_ENV=1, SB_POSE=2 } SbRole;
typedef enum { SB_INPUT_ENV, SB_INPUT_POSE, SB_INPUT_LOCATION, SB_INPUT_COUNT } SbInputKind;
typedef enum { SB_SOURCE_MOCK=1, SB_SOURCE_REAL=2 } SbSource;
typedef enum { SB_UNSYNCED=0, SB_SNTP=1 } SbTimeQuality;
typedef enum { SB_LIGHT, SB_FAN, SB_INDICATOR, SB_OUTPUT_COUNT } SbOutputKind;
typedef struct { SbRole role; const char *box_id; const char *node_id;
    uint32_t capabilities, sample_ms, location_ms; } SbConfig;
typedef struct { uint64_t uptime_ms; bool utc_valid; int64_t utc_ms;
    uint32_t time_uncertainty_ms; SbTimeQuality quality; } SbClock;
typedef struct { SbRole role; SbInputKind kind; SbSource source; SbClock clock; bool valid; uint32_t sample_seq;
    union { struct { float temperature_c, humidity_pct, light_lux; } env;
        struct { float x_g,y_g,z_g,tilt_deg; } pose;
        struct { double latitude_deg,longitude_deg; } location; } value;
} SbSensorFrame;
typedef struct { SbOutputKind kind; bool on; uint32_t duration_ms; } SbOutputRequest;
typedef struct { bool on; uint64_t expires_ms; uint64_t changed_ms; } SbOutputState;
typedef struct { SbSensorFrame frames[SB_INPUT_COUNT]; bool present[SB_INPUT_COUNT];
    uint64_t processed; } SbSnapshot;
typedef enum {
    SB_EVENT_NONE=0, SB_EVENT_ENV_HIGH, SB_EVENT_ENV_LOW, SB_EVENT_ENV_RECOVERED,
    SB_EVENT_POSE_CHANGED, SB_EVENT_IMPACT, SB_EVENT_DOOR_EVIDENCE,
    SB_EVENT_GEOFENCE_EXIT, SB_EVENT_GEOFENCE_ENTER, SB_EVENT_SENTRY_INTRUSION
} SbEventType;
typedef enum { SB_LEVEL_INFO=1, SB_LEVEL_WARNING=2, SB_LEVEL_CRITICAL=3 } SbEventLevel;
typedef enum { SB_DOOR_UNKNOWN=0, SB_DOOR_OPEN, SB_DOOR_CLOSED } SbDoorState;
typedef enum { SB_GPS_FIXED=0, SB_GPS_ROUTE, SB_GPS_WALK } SbGpsMode;
typedef struct { uint64_t uptime_ms; uint32_t diagnostic_code; SbSource source;
    bool valid; SbEventType type; SbEventLevel level; char data_json[160]; } SbEventCandidate;
typedef enum { SB_BOOTSTRAP, SB_READY, SB_FAILED } SbStartupState;
typedef struct { SbStartupState state; uint32_t created, exited;
    uint64_t sample_queue_full, candidate_full, missed_periods, sensor_errors, network_disabled;
    uint64_t sampled, interval_min_ms, interval_max_ms;
    bool reset_required; const char *failure; } SbAppStatus;
#endif
