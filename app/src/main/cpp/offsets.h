#pragma once

// ============================================================================
// PROINJECTOR - MEMORY OFFSETS & PATCH CONFIGURATION
// ============================================================================
// The native engine dynamically parses /proc/[pid]/maps to locate the base
// address of TARGET_LIB (e.g., libil2cpp.so or libunity.so) and calculates
// target effective memory addresses as: (g_lib_base + offset).
// ============================================================================

#include <cstdint>
#include <cstddef>

// Default target shared library (modify to libunity.so, libanogs.so, etc. as needed)
#define TARGET_LIB "libil2cpp.so"

namespace Offsets {

    // Feature definition structure
    struct Feature {
        uint64_t offset;        // Relative offset from TARGET_LIB base address
        uint8_t  bytes[8];      // Payload patch bytes to inject (instructions / float / int)
        uint8_t  size;          // Payload byte length (usually 4 bytes for ARM instructions/floats)
        uint8_t  orig[8];       // Original memory snapshot (captured dynamically on init)
        bool     orig_captured; // Tracks whether original memory has been captured
    };

    // ========================================================================
    // FEATURE OFFSETS TABLE
    // Indexes (0..7) directly map to the toggle switches in FloatingService.kt
    // ========================================================================
    static Feature FEATURES[] = {
        /* [0] God Mode      */ { 0x3A5F1C0, {0xFF, 0x7F, 0x4F, 0x46}, 4, {0x00, 0x00, 0x00, 0x00}, false }, // float 99999.0f
        /* [1] Infinite Ammo */ { 0x3A61A44, {0x00, 0x00, 0x40, 0x46}, 4, {0x00, 0x00, 0x00, 0x00}, false }, // float 12.0f
        /* [2] No Recoil     */ { 0x1C2E5A0, {0x00, 0x00, 0x00, 0x00}, 4, {0x00, 0x00, 0x00, 0x00}, false }, // NOP / zero vector
        /* [3] Speed Boost   */ { 0x2F8A120, {0x00, 0x00, 0x04, 0x3F}, 4, {0x00, 0x00, 0x00, 0x00}, false }, // float 2.0f
        /* [4] Teleport      */ { 0x3B10078, {0x00, 0x00, 0x00, 0x00}, 4, {0x00, 0x00, 0x00, 0x00}, false },
        /* [5] Anti-Aim      */ { 0x2D44C90, {0xC0, 0x3F, 0x00, 0x00}, 4, {0x00, 0x00, 0x00, 0x00}, false },
        /* [6] Radar ESP     */ { 0x4A82E10, {0x01, 0x00, 0x00, 0x00}, 4, {0x00, 0x00, 0x00, 0x00}, false }, // int 1 (enabled)
        /* [7] Magic Bullet  */ { 0x5021D4C, {0x01, 0x00, 0x00, 0x00}, 4, {0x00, 0x00, 0x00, 0x00}, false }, // int 1 (enabled)
    };

    static const int FEATURE_COUNT = sizeof(FEATURES) / sizeof(FEATURES[0]);

} // namespace Offsets
