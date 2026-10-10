#pragma once

#include <libusb.h>

enum class UsbReadAction { process, retry, stop };

class UsbStreamReadPolicy {
public:
    UsbReadAction received(int result, int bytes) {
        // A timed-out bulk transfer may still contain valid IQ, including an
        // odd byte count. AmPipeline retains the unmatched byte across calls.
        if ((result == 0 || result == LIBUSB_ERROR_TIMEOUT) && bytes > 0) {
            failures_ = 0;
            return UsbReadAction::process;
        }
        if (result == LIBUSB_ERROR_NO_DEVICE || ++failures_ >= 10) return UsbReadAction::stop;
        return UsbReadAction::retry;
    }

private:
    int failures_ = 0;
};
