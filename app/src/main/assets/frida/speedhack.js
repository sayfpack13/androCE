'use strict';
/* Bundled for on-device Frida (Termux + frida-server). */
var SPEED = 1.0;
var baseMono = null;
var baseRealtime = null;

if (globalThis.__androce_frida) {
    console.log('[androce] script already loaded');
} else {
    globalThis.__androce_frida = true;

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
            if (clk !== 1 && clk !== 4 && clk !== 7) return;
            var sec = this.tp.readU64 ? Number(this.tp.readU64()) : this.tp.readS64();
            var nsec = this.tp.add(8).readU64 ? Number(this.tp.add(8).readU64()) : this.tp.add(8).readLong();
            if (baseMono === null) {
                baseMono = { sec: sec, nsec: nsec };
                return;
            }
            var nowUs = sec * 1000000 + Math.floor(nsec / 1000);
            var baseUs = baseMono.sec * 1000000 + Math.floor(baseMono.nsec / 1000);
            var diff = nowUs - baseUs;
            if (diff < 0) diff = 0;
            var scaled = baseUs + diff * SPEED;
            var outSec = Math.floor(scaled / 1000000);
            var outNsec = (scaled % 1000000) * 1000;
            if (this.tp.writeU64) {
                this.tp.writeU64(outSec);
                this.tp.add(8).writeU64(outNsec);
            } else {
                this.tp.writeS64(outSec);
                this.tp.add(8).writeLong(outNsec);
            }
        }
    });
    console.log('[androce] clock_gettime hooked speed=' + SPEED);
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
            var sec = this.tv.readU64 ? Number(this.tv.readU64()) : this.tv.readS64();
            var usec = this.tv.add(8).readU64 ? Number(this.tv.add(8).readU64()) : this.tv.add(8).readLong();
            if (baseRealtime === null) {
                baseRealtime = { sec: sec, usec: usec };
                return;
            }
            var nowUs = sec * 1000000 + usec;
            var baseUs = baseRealtime.sec * 1000000 + baseRealtime.usec;
            var diff = nowUs - baseUs;
            if (diff < 0) diff = 0;
            var scaled = baseUs + diff * SPEED;
            var outSec = Math.floor(scaled / 1000000);
            var outUsec = scaled % 1000000;
            if (this.tv.writeU64) {
                this.tv.writeU64(outSec);
                this.tv.add(8).writeU64(outUsec);
            } else {
                this.tv.writeS64(outSec);
                this.tv.add(8).writeLong(outUsec);
            }
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

} /* end first load */
