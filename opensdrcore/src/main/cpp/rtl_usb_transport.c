/* Compile the pinned upstream driver with bounded synchronous bulk reads.
 * Keep this adaptation here rather than making untracked edits in the external
 * rtl-sdr checkout. Asynchronous transfers and their timeouts are unchanged.
 */
#include <libusb.h>

static int opensdr_bulk_transfer(libusb_device_handle *device, unsigned char endpoint,
        unsigned char *data, int length, int *transferred, unsigned int timeout)
{
    return libusb_bulk_transfer(device, endpoint, data, length, transferred,
        timeout == 0 ? 200 : timeout);
}

#define libusb_bulk_transfer opensdr_bulk_transfer
#include "librtlsdr.c"
#undef libusb_bulk_transfer
