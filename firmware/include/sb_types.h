#ifndef SB_TYPES_H
#define SB_TYPES_H
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
typedef enum { SB_OK, SB_INVALID_ARG, SB_UNSUPPORTED_CAP, SB_NO_DATA,
    SB_FULL, SB_NOT_IMPLEMENTED, SB_PLATFORM_ERROR } SbResult;
typedef enum { SB_ENV=1, SB_POSE=2 } SbRole;
typedef enum { SB_INPUT_ENV, SB_INPUT_POSE, SB_INPUT_LOCATION, SB_INPUT_COUNT } SbInputKind;
typedef enum { SB_SOURCE_MOCK=1 } SbSource;
typedef enum { SB_UNSYNCED=0 } SbTimeQuality;
typedef enum { SB_LIGHT, SB_FAN, SB_INDICATOR, SB_OUTPUT_COUNT } SbOutputKind;
typedef struct { SbRole role; const char *box_id; const char *node_id;
    uint32_t capabilities, sample_ms, location_ms; } SbConfig;
typedef struct { uint64_t uptime_ms; bool utc_valid; int64_t utc_ms;
    uint32_t time_uncertainty_ms; SbTimeQuality quality; } SbClock;
typedef struct { SbRole role; SbInputKind kind; SbSource source; SbClock clock; bool valid;
    union { struct { float temperature_c, humidity_pct, light_lux; } env;
        struct { float x_g,y_g,z_g,tilt_deg; } pose;
        struct { double latitude_deg,longitude_deg; } location; } value;
} SbSensorFrame;
typedef struct { uint64_t uptime_ms; uint32_t diagnostic_code; SbSource source;
    bool valid; uint8_t data[32]; } SbEventCandidate;
typedef struct { SbOutputKind kind; bool on; uint32_t duration_ms; } SbOutputRequest;
typedef struct { bool on; uint64_t expires_ms; } SbOutputState;
typedef struct { SbSensorFrame frames[SB_INPUT_COUNT]; bool present[SB_INPUT_COUNT];
    uint64_t processed; } SbSnapshot;
typedef enum { SB_BOOTSTRAP, SB_READY, SB_FAILED } SbStartupState;
typedef struct { SbStartupState state; uint32_t created, exited;
    uint64_t sample_queue_full, candidate_full, missed_periods, network_disabled;
    bool reset_required; const char *failure; } SbAppStatus;
#endif
