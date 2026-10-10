#include "squelch_meter.h"
#include "usb_stream_read.h"
#include <cstdlib>
#include <iostream>

void check(bool condition, const char* message) {
    if (!condition) { std::cerr << message << '\n'; std::exit(1); }
}

int main() {
    SquelchMeter meter;
    meter.configure(100, 200);
    for (int t = 0; t < 100; t += 10) meter.observe(90, t);
    check(meter.percent(90) == 0, "A sub-100ms impulse must not qualify");
    meter.observe(20, 100);
    for (int t = 110; t <= 300; t += 10) meter.observe(20, t);
    check(meter.percent(300) == 20, "Sustained background level");
    for (int t = 310; t <= 450; t += 10) meter.observe(60, t);
    check(meter.percent(450) == 60, "Sustained signal must qualify");
    meter.observe(20, 460);
    check(meter.percent(650) == 60, "Keep the qualified peak during hang time");
    check(meter.percent(676) == 20, "Expire the high peak before the newer background sample");
    check(meter.percent(686) == 0, "Stale samples must expire without new USB data");
    meter.reset();
    check(meter.percent(700) == 0, "Retune clears the meter");
    for (int t = 0; t <= 100; ++t) meter.observe(100, t);
    check(meter.percent(100) == 100, "Full scale is valid");
    meter.configure(300, 200);
    check(meter.percent(100) == 0, "Timing changes require requalification");
    meter.observe(80, 200);
    meter.observe(80, 2000);
    check(meter.percent(2000) == 0, "A long USB stall must not count as sustained signal");

    UsbStreamReadPolicy reads;
    check(reads.received(LIBUSB_ERROR_TIMEOUT, 4095) == UsbReadAction::process, "Preserve partial/odd timed-out IQ");
    for (int n = 0; n < 9; ++n)
        check(reads.received(LIBUSB_ERROR_TIMEOUT, 0) == UsbReadAction::retry, "Retry transient timeout");
    check(reads.received(0, 4096) == UsbReadAction::process, "Success resets the retry counter");
    for (int n = 0; n < 9; ++n) reads.received(0, 0);
    check(reads.received(0, 0) == UsbReadAction::stop, "Bound empty-read retries");
    check(reads.received(LIBUSB_ERROR_NO_DEVICE, 0) == UsbReadAction::stop, "Stop on device removal");
    std::cout << "Squelch meter and USB read policy tests passed\n";
}
