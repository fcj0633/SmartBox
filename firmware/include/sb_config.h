#ifndef SB_CONFIG_H
#define SB_CONFIG_H
#include "sb_types.h"
#if defined(SB_ROLE_ENV) && defined(SB_ROLE_POSE)
#error "Select exactly one SmartBox role"
#elif defined(SB_ROLE_ENV)
#if SB_ROLE_ENV != 1
#error "SB_ROLE_ENV must be 1"
#endif
#elif defined(SB_ROLE_POSE)
#if SB_ROLE_POSE != 1
#error "SB_ROLE_POSE must be 1"
#endif
#else
#error "Select SB_ROLE_ENV=1 or SB_ROLE_POSE=1"
#endif
const SbConfig *sb_config_get(void);
SbResult sb_config_validate(void);
#endif
