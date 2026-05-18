/**
 * Frida speedhack — use when native ptrace injection fails (e.g. Android 15 + MTE).
 *
 * From a PC with frida-tools installed and frida-server on the device:
 *   frida -U -f com.kiloo.subwaysurf -l tools/frida_speedhack.js --no-pause
 *   frida -U -n com.kiloo.subwaysurf -l tools/frida_speedhack.js
 *
 * Adjust SPEED below or call: rpc.exports.setSpeed(2.0)
 */
'use strict';

var SPEED = 1.0;
var baseMono = null;
var baseRealtime = null;

function scaleTimespec(tp, base) {
    if (!tp || tp.isNull()) return;
    var sec = tp.readU32 ? tp.readU32() : tp.readLong();
    // struct timespec { time_t tv_sec; long tv_nsec } — use NativeFunction for portability
}

function hookClockGettime() {
    var sym = Module.findExportByName('libc.so', 'clock_gettime');
    if (!sym) {
        console.log('[androce] clock_gettime not found');
        return;
    }
    Interceptor.attach(sym, {
        onEnter: function (args) {
            this.clk = args[0].toInt32();
            this.tp = args[1];
        },
        onLeave: function (retval) {
            if (retval.toInt32() !== 0 || this.tp.isNull()) return;
            var clk = this.clk;
            if (clk !== 1 && clk !== 4 && clk !== 7) return; /* MONOTONIC, RAW, BOOTTIME */
            if (baseMono === null) {
                baseMono = {
                    sec: this.tp.readS64(),
                    nsec: this.tp.add(8).readLong()
                };
                return;
            }
            var sec = this.tp.readS64();
            var nsec = this.tp.add(8).readLong();
            var nowUs = sec * 1000000 + nsec / 1000;
            var baseUs = baseMono.sec * 1000000 + baseMono.nsec / 1000;
            var diff = nowUs - baseUs;
            if (diff < 0) diff = 0;
            var scaled = baseUs + diff * SPEED;
            this.tp.writeS64(Math.floor(scaled / 1000000));
            this.tp.add(8).writeLong((scaled % 1000000) * 1000);
        }
    });
    console.log('[androce] clock_gettime hooked, speed=' + SPEED);
}

function hookGettimeofday() {
    var sym = Module.findExportByName('libc.so', 'gettimeofday');
    if (!sym) return;
    Interceptor.attach(sym, {
        onEnter: function (args) {
            this.tv = args[0];
        },
        onLeave: function (retval) {
            if (retval.toInt32() !== 0 || this.tv.isNull()) return;
            if (baseRealtime === null) {
                baseRealtime = {
                    sec: this.tv.readS64(),
                    usec: this.tv.add(8).readLong()
                };
                return;
            }
            var sec = this.tv.readS64();
            var usec = this.tv.add(8).readLong();
            var nowUs = sec * 1000000 + usec;
            var baseUs = baseRealtime.sec * 1000000 + baseRealtime.usec;
            var diff = nowUs - baseUs;
            if (diff < 0) diff = 0;
            var scaled = baseUs + diff * SPEED;
            this.tv.writeS64(Math.floor(scaled / 1000000));
            this.tv.add(8).writeLong(scaled % 1000000);
        }
    });
    console.log('[androce] gettimeofday hooked');
}

rpc.exports = {
    setSpeed: function (s) {
        SPEED = s;
        console.log('[androce] speed=' + SPEED);
    }
};

hookClockGettime();
hookGettimeofday();
console.log('[androce] Frida speedhack ready');
