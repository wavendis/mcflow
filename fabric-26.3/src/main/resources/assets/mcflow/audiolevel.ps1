# Prints the bass energy (kick drum range) of everything playing on this PC's outputs, every 20 ms.
# Records each active output device in "loopback" mode (what screen recorders do), low-passes it at ~150 Hz and
# prints the mean square of each 20 ms block. Exits when the parent process (Minecraft) is gone.
param([int]$ParentPid)
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;

[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorCom {}

[ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator {
    int EnumAudioEndpoints(int dataFlow, int stateMask, out IMMDeviceCollection devices);
}

[ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceCollection {
    int GetCount(out int count);
    int Item(int index, out IMMDevice device);
}

[ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice {
    int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
}

[ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioClient {
    int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, IntPtr format, IntPtr sessionGuid);
    int GetBufferSize(out uint frames);
    int GetStreamLatency(out long latency);
    int GetCurrentPadding(out uint padding);
    int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
    int GetMixFormat(out IntPtr format);
    int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
    int Start();
    int Stop();
    int Reset();
    int SetEventHandle(IntPtr handle);
    int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
}

[ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioCaptureClient {
    int GetBuffer(out IntPtr data, out int frames, out int flags, out long devicePosition, out long qpcPosition);
    int ReleaseBuffer(int frames);
    int GetNextPacketSize(out int frames);
}

public static class Bass {
    class Capture {
        public IAudioClient client;
        public IAudioCaptureClient capture;
        public int channels, bits;
        public bool isFloat;
        public double alpha, lowPass, energy;
        public int samples;
    }

    static List<Capture> captures = new List<Capture>();

    /** Starts loopback capture on every active output device. */
    public static void Open() {
        foreach (var c in captures) { try { c.client.Stop(); } catch {} }
        captures.Clear();
        var enumerator = (IMMDeviceEnumerator) new MMDeviceEnumeratorCom();
        IMMDeviceCollection devices;
        enumerator.EnumAudioEndpoints(0, 1, out devices); // eRender, DEVICE_STATE_ACTIVE
        int count;
        devices.GetCount(out count);
        for (int d = 0; d < count; d++) {
            try {
                IMMDevice device;
                devices.Item(d, out device);
                Guid iid = typeof(IAudioClient).GUID;
                object o;
                device.Activate(ref iid, 23, IntPtr.Zero, out o); // CLSCTX_ALL
                var c = new Capture { client = (IAudioClient) o };
                IntPtr fmt;
                c.client.GetMixFormat(out fmt);
                int tag = Marshal.ReadInt16(fmt, 0);
                c.channels = Marshal.ReadInt16(fmt, 2);
                int rate = Marshal.ReadInt32(fmt, 4);
                c.bits = Marshal.ReadInt16(fmt, 14);
                // WAVE_FORMAT_IEEE_FLOAT, or EXTENSIBLE (whose shared-mode mix format is float at 32 bits)
                c.isFloat = tag == 3 || (tag == unchecked((short) 0xFFFE) && c.bits == 32);
                c.alpha = 1 - Math.Exp(-2 * Math.PI * 150.0 / rate); // one-pole low-pass at ~150 Hz
                // shared mode, AUDCLNT_STREAMFLAGS_LOOPBACK, 200 ms buffer
                if (c.client.Initialize(0, 0x00020000, 2000000, 0, fmt, IntPtr.Zero) != 0) continue;
                Guid cap = typeof(IAudioCaptureClient).GUID;
                object svc;
                c.client.GetService(ref cap, out svc);
                c.capture = (IAudioCaptureClient) svc;
                c.client.Start();
                captures.Add(c);
            } catch {}
        }
    }

    /** Reads whatever audio arrived since the last call into each device's running bass energy. */
    static void Drain() {
        foreach (var c in captures) {
            int packet;
            while (c.capture.GetNextPacketSize(out packet) == 0 && packet > 0) {
                IntPtr data; int frames, flags; long devPos, qpc;
                if (c.capture.GetBuffer(out data, out frames, out flags, out devPos, out qpc) != 0) break;
                bool silent = (flags & 2) != 0;
                int bytes = c.bits / 8;
                for (int f = 0; f < frames; f++) {
                    double mono = 0;
                    if (!silent) {
                        for (int ch = 0; ch < c.channels; ch++) {
                            int offset = (f * c.channels + ch) * bytes;
                            mono += c.isFloat ? BitConverter.ToSingle(BitConverter.GetBytes(Marshal.ReadInt32(data, offset)), 0)
                                              : Marshal.ReadInt16(data, offset) / 32768.0;
                        }
                        mono /= c.channels;
                    }
                    c.lowPass += (mono - c.lowPass) * c.alpha;
                    c.energy += c.lowPass * c.lowPass;
                    c.samples++;
                }
                c.capture.ReleaseBuffer(frames);
            }
        }
    }

    /** Prints bass energy every 20 ms until the parent process exits. */
    public static void Run(int parentPid) {
        Open();
        var clock = Stopwatch.StartNew();
        long nextReopen = 5000, nextParentCheck = 2000;
        while (true) {
            Thread.Sleep(20);
            Drain();
            double total = 0;
            foreach (var c in captures) {
                if (c.samples > 0) total += c.energy / c.samples;
                c.energy = 0;
                c.samples = 0;
            }
            Console.Out.WriteLine(total.ToString("0.000000", System.Globalization.CultureInfo.InvariantCulture));
            Console.Out.Flush();
            if (clock.ElapsedMilliseconds > nextParentCheck) {
                nextParentCheck += 2000;
                try { if (parentPid != 0 && Process.GetProcessById(parentPid).HasExited) return; } catch (ArgumentException) { return; }
            }
            if (clock.ElapsedMilliseconds > nextReopen) { nextReopen += 5000; if (captures.Count == 0) Open(); }
        }
    }
}
'@
[Bass]::Run($ParentPid)
