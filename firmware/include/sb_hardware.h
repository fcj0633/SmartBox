#ifndef SB_HARDWARE_H
#define SB_HARDWARE_H
#include "sb_ports.h"
/* Only the sampling thread accesses I2C after startup. Addresses are 7-bit. */
SbResult sb_hw_i2c_init(void);
SbResult sb_hw_write(uint8_t address, const uint8_t *data, size_t length);
SbResult sb_hw_read(uint8_t address, const uint8_t *prefix, size_t prefix_length,
                    uint8_t *data, size_t length);
SbResult sb_hw_gpio_init(void);
SbResult sb_hw_gpio(SbOutputKind kind, bool on);
void sb_hw_delay(uint32_t ms);
/* Slot read distinguishes an absent record from an I/O error. */
SbResult sb_boot_read(unsigned slot, char *data, size_t size);
SbResult sb_boot_write(unsigned slot, const char *data);
SbResult sb_boot_reserve(uint32_t provisioned_floor, uint32_t *boot_id);
SbResult sb_clock_sync(int64_t utc_ms, uint32_t uncertainty_ms);
#endif
