import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:geolocator/geolocator.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:wifi_iot/wifi_iot.dart';
import 'package:http/http.dart' as http;

void main() {
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: Brightness.dark,
  ));
  runApp(const SmartBinApp());
}

/* ============================================================
   THEME — industrial light, deep emerald primary
   ============================================================ */
const _kPrimary    = Color(0xFF059669);
const _kPrimaryDk  = Color(0xFF064E3B);
const _kAccent     = Color(0xFF0EA5A4);
const _kBgTop      = Color(0xFFF0FDF4);
const _kBinApPass  = 'SmartBin@2024';   // shared SoftAP password (matches firmware)
const _kBgBottom   = Color(0xFFE6F4EA);
const _kCard       = Colors.white;
const _kInk        = Color(0xFF0F172A);
const _kInkMuted   = Color(0xFF64748B);
const _kBorder     = Color(0xFFE2E8F0);
const _kDanger     = Color(0xFFE11D48);
const _kAmber      = Color(0xFFF59E0B);

class SmartBinApp extends StatelessWidget {
  const SmartBinApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'SmartBin Provisioner',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
          useMaterial3: true,
          colorScheme: ColorScheme.fromSeed(seedColor: _kPrimary, primary: _kPrimary),
          scaffoldBackgroundColor: _kBgTop,
          fontFamily: 'Roboto',
        ),
        home: const OpeningSplash(),
      );
}

class OpeningSplash extends StatefulWidget {
  const OpeningSplash({super.key});

  @override
  State<OpeningSplash> createState() => _OpeningSplashState();
}

class _OpeningSplashState extends State<OpeningSplash> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1900),
  )..forward();

  @override
  void initState() {
    super.initState();
    Future.delayed(const Duration(milliseconds: 2300), () {
      if (!mounted) return;
      Navigator.of(context).pushReplacement(
        PageRouteBuilder(
          transitionDuration: const Duration(milliseconds: 520),
          pageBuilder: (_, __, ___) => const ProvisionWizard(),
          transitionsBuilder: (_, animation, __, child) {
            return FadeTransition(
              opacity: CurvedAnimation(parent: animation, curve: Curves.easeOutCubic),
              child: child,
            );
          },
        ),
      );
    });
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: _AnimatedBackdrop(
        child: Center(
          child: AnimatedBuilder(
            animation: _c,
            builder: (_, __) {
              final logoIn = CurvedAnimation(
                parent: _c,
                curve: const Interval(0, 0.62, curve: Curves.elasticOut),
              ).value;
              final textIn = CurvedAnimation(
                parent: _c,
                curve: const Interval(0.34, 0.78, curve: Curves.easeOutCubic),
              ).value;
              final lineIn = CurvedAnimation(
                parent: _c,
                curve: const Interval(0.55, 1, curve: Curves.easeInOut),
              ).value;

              return Column(mainAxisSize: MainAxisSize.min, children: [
                Transform.scale(
                  scale: 0.7 + (logoIn * 0.3),
                  child: Opacity(
                    opacity: logoIn.clamp(0, 1),
                    child: Container(
                      width: 96,
                      height: 96,
                      decoration: BoxDecoration(
                        gradient: const LinearGradient(
                          begin: Alignment.topLeft,
                          end: Alignment.bottomRight,
                          colors: [_kPrimary, _kAccent],
                        ),
                        borderRadius: BorderRadius.circular(24),
                        boxShadow: [
                          BoxShadow(
                            color: _kPrimary.withOpacity(0.24),
                            blurRadius: 28,
                            offset: const Offset(0, 14),
                          ),
                        ],
                      ),
                      child: const Icon(Icons.delete_outline, color: Colors.white, size: 54),
                    ),
                  ),
                ),
                const SizedBox(height: 22),
                Opacity(
                  opacity: textIn.clamp(0, 1),
                  child: Transform.translate(
                    offset: Offset(0, 18 * (1 - textIn)),
                    child: const Column(children: [
                      Text(
                        'SmartBin',
                        style: TextStyle(
                          color: _kInk,
                          fontSize: 28,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      SizedBox(height: 4),
                      Text(
                        'Field Provisioner',
                        style: TextStyle(color: _kInkMuted, fontSize: 13),
                      ),
                    ]),
                  ),
                ),
                const SizedBox(height: 28),
                SizedBox(
                  width: 120,
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(99),
                    child: LinearProgressIndicator(
                      value: lineIn,
                      minHeight: 4,
                      backgroundColor: _kBorder,
                      valueColor: const AlwaysStoppedAnimation(_kPrimary),
                    ),
                  ),
                ),
              ]);
            },
          ),
        ),
      ),
    );
  }
}

/* ============================================================
   COLLECTED DATA
   ============================================================ */
class ProvData {
  double? lat, lng;
  double? accuracy;
  String? binSsid;          // SMARTBIN-xxxx (the device hotspot)
  String wifiSsid = '';
  String wifiPass = '';

  String binName = '';
  String ward = '';
  String route = '';
  String place = '';
}

/* ============================================================
   ROOT WIZARD
   ============================================================ */
class ProvisionWizard extends StatefulWidget {
  const ProvisionWizard({super.key});
  @override
  State<ProvisionWizard> createState() => _ProvisionWizardState();
}

class _ProvisionWizardState extends State<ProvisionWizard> with TickerProviderStateMixin {
  final _pc = PageController();
  final _data = ProvData();
  int _step = 0;
  static const _labels = ['Location', 'Bin', 'WiFi', 'Details', 'Save'];

  void _resetToHome() {
    _data
      ..lat = null
      ..lng = null
      ..accuracy = null
      ..binSsid = null
      ..wifiSsid = ''
      ..wifiPass = ''
      ..binName = ''
      ..ward = ''
      ..route = ''
      ..place = '';
    _go(0);
  }

  void _go(int i) {
    setState(() => _step = i);
    _pc.animateToPage(i, duration: const Duration(milliseconds: 380), curve: Curves.easeOutCubic);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: _AnimatedBackdrop(
          child: Column(children: [
            _Header(step: _step, labels: _labels),
            Expanded(
              child: PageView(
                controller: _pc,
                physics: const NeverScrollableScrollPhysics(),
                children: [
                  Step1Location(data: _data, onNext: () => _go(1)),
                  Step2ConnectBin(data: _data, onNext: () => _go(2), onBack: () => _go(0)),
                  Step3WiFi(data: _data, onNext: () => _go(3), onBack: () => _go(1)),
                  Step4Details(data: _data, onNext: () => _go(4), onBack: () => _go(2)),
                  Step5Save(data: _data, onDone: _resetToHome),
                ],
              ),
            ),
          ]),
        ),
      ),
    );
  }
}

class _AnimatedBackdrop extends StatefulWidget {
  final Widget child;
  const _AnimatedBackdrop({required this.child});

  @override
  State<_AnimatedBackdrop> createState() => _AnimatedBackdropState();
}

class _AnimatedBackdropState extends State<_AnimatedBackdrop> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(
    vsync: this,
    duration: const Duration(seconds: 7),
  )..repeat(reverse: true);

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _c,
      builder: (_, __) {
        final t = Curves.easeInOut.transform(_c.value);
        return DecoratedBox(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment(-0.35 + (t * 0.25), -1),
              end: Alignment(0.45 - (t * 0.2), 1),
              colors: const [
                Color(0xFFF3FFF8),
                Color(0xFFE9F8EF),
                Color(0xFFE8F4F8),
              ],
            ),
          ),
          child: Stack(children: [
            Positioned(
              left: 0,
              right: 0,
              top: 84 + (t * 14),
              child: Opacity(
                opacity: 0.24,
                child: Container(
                  height: 2,
                  decoration: const BoxDecoration(
                    gradient: LinearGradient(
                      colors: [Colors.transparent, _kAccent, Colors.transparent],
                    ),
                  ),
                ),
              ),
            ),
            widget.child,
          ]),
        );
      },
    );
  }
}

/* ============================================================
   HEADER + animated progress dots
   ============================================================ */
class _Header extends StatelessWidget {
  final int step; final List<String> labels;
  const _Header({required this.step, required this.labels});
  @override
  Widget build(BuildContext c) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 8),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          const _BreathingBinMark(),
          const SizedBox(width: 10),
          const Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('SmartBin', style: TextStyle(fontWeight: FontWeight.w700, color: _kInk, fontSize: 16)),
            Text('Field Provisioner', style: TextStyle(color: _kInkMuted, fontSize: 11)),
          ]),
          const Spacer(),
          Text('Step ${step + 1}/${labels.length}', style: const TextStyle(color: _kInkMuted, fontSize: 12)),
        ]),
        const SizedBox(height: 14),
        TweenAnimationBuilder<double>(
          tween: Tween(begin: 0, end: (step + 1) / labels.length),
          duration: const Duration(milliseconds: 450), curve: Curves.easeOutCubic,
          builder: (_, v, __) => ClipRRect(
            borderRadius: BorderRadius.circular(99),
            child: LinearProgressIndicator(
              value: v, minHeight: 6, backgroundColor: _kBorder, valueColor: const AlwaysStoppedAnimation(_kPrimary),
            ),
          ),
        ),
        const SizedBox(height: 8),
        Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
          for (int i = 0; i < labels.length; i++)
            AnimatedDefaultTextStyle(
              duration: const Duration(milliseconds: 250),
              style: TextStyle(
                fontSize: 11,
                fontWeight: i == step ? FontWeight.w600 : FontWeight.w400,
                color: i <= step ? _kPrimaryDk : _kInkMuted,
              ),
              child: Text(labels[i]),
            ),
        ]),
      ]),
    );
  }
}

class _BreathingBinMark extends StatefulWidget {
  const _BreathingBinMark();

  @override
  State<_BreathingBinMark> createState() => _BreathingBinMarkState();
}

class _BreathingBinMarkState extends State<_BreathingBinMark> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1800),
  )..repeat(reverse: true);

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _c,
      builder: (_, __) {
        final t = Curves.easeInOut.transform(_c.value);
        return Transform.scale(
          scale: 1 + (t * 0.035),
          child: Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [_kPrimary, _kAccent],
              ),
              borderRadius: BorderRadius.circular(11),
              boxShadow: [
                BoxShadow(
                  color: _kPrimary.withOpacity(0.18 + (t * 0.08)),
                  blurRadius: 14 + (t * 8),
                  offset: const Offset(0, 5),
                ),
              ],
            ),
            child: const Icon(Icons.delete_outline, color: Colors.white, size: 23),
          ),
        );
      },
    );
  }
}

/* ============================================================
   Reusable card + buttons
   ============================================================ */
class _Card extends StatelessWidget {
  final Widget child;
  const _Card({required this.child});
  @override
  Widget build(BuildContext c) => Container(
        margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        padding: const EdgeInsets.all(18),
        decoration: BoxDecoration(
          color: _kCard, borderRadius: BorderRadius.circular(18),
          border: Border.all(color: _kBorder),
          boxShadow: const [BoxShadow(color: Color(0x0A000000), blurRadius: 16, offset: Offset(0, 4))],
        ),
        child: child,
      );
}

class _PrimaryButton extends StatelessWidget {
  final String label; final IconData icon; final VoidCallback? onTap; final bool busy;
  const _PrimaryButton({required this.label, required this.icon, this.onTap, this.busy = false});
  @override
  Widget build(BuildContext c) => SizedBox(
        height: 52, width: double.infinity,
        child: ElevatedButton.icon(
          onPressed: busy ? null : onTap,
          icon: busy
              ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : Icon(icon, color: Colors.white),
          label: Text(label, style: const TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.w600)),
          style: ElevatedButton.styleFrom(
            backgroundColor: _kPrimary, disabledBackgroundColor: _kPrimary.withOpacity(0.5),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
            elevation: 0,
          ),
        ),
      );
}

class _GhostButton extends StatelessWidget {
  final String label; final VoidCallback? onTap;
  const _GhostButton({required this.label, this.onTap});
  @override
  Widget build(BuildContext c) => TextButton(
        onPressed: onTap,
        style: TextButton.styleFrom(foregroundColor: _kPrimaryDk),
        child: Text(label),
      );
}

/* ============================================================
   STEP 1 — Location (animated radar while acquiring)
   ============================================================ */
class Step1Location extends StatefulWidget {
  final ProvData data; final VoidCallback onNext;
  const Step1Location({super.key, required this.data, required this.onNext});
  @override
  State<Step1Location> createState() => _Step1State();
}
class _Step1State extends State<Step1Location> with SingleTickerProviderStateMixin {
  late final AnimationController _radar;
  String status = 'Acquiring your GPS location…';
  bool busy = true;

  @override
  void initState() {
    super.initState();
    _radar = AnimationController(vsync: this, duration: const Duration(seconds: 2))..repeat();
    _getLocation();
  }
  @override void dispose() { _radar.dispose(); super.dispose(); }

  Future<void> _getLocation() async {
    setState(() { busy = true; status = 'Acquiring your GPS location…'; });
    await Permission.location.request();
    if (!await Geolocator.isLocationServiceEnabled()) {
      setState(() { busy = false; status = 'Turn on phone Location, then tap Retry.'; });
      return;
    }
    var perm = await Geolocator.checkPermission();
    if (perm == LocationPermission.denied) perm = await Geolocator.requestPermission();
    if (perm == LocationPermission.denied || perm == LocationPermission.deniedForever) {
      setState(() { busy = false; status = 'Location permission denied.'; });
      return;
    }
    try {
      final p = await Geolocator.getCurrentPosition(desiredAccuracy: LocationAccuracy.high);
      widget.data.lat = p.latitude;
      widget.data.lng = p.longitude;
      widget.data.accuracy = p.accuracy;
      setState(() { busy = false; status = 'Location captured.'; });
    } catch (e) {
      setState(() { busy = false; status = 'Could not get GPS: $e'; });
    }
  }

  @override
  Widget build(BuildContext c) {
    final d = widget.data;
    return SingleChildScrollView(
      child: Column(children: [
        const SizedBox(height: 20),
        SizedBox(
          height: 220,
          child: AnimatedBuilder(
            animation: _radar,
            builder: (_, __) {
              final t = _radar.value;
              return Stack(alignment: Alignment.center, children: [
                for (final r in [t, (t + 0.33) % 1, (t + 0.66) % 1])
                  Container(
                    width: 200 * r, height: 200 * r,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: _kPrimary.withOpacity((1 - r) * 0.12),
                      border: Border.all(color: _kPrimary.withOpacity((1 - r) * 0.4)),
                    ),
                  ),
                Container(
                  width: 76, height: 76,
                  decoration: const BoxDecoration(color: _kPrimary, shape: BoxShape.circle),
                  child: const Icon(Icons.my_location, color: Colors.white, size: 34),
                ),
              ]);
            },
          ),
        ),
        _Card(
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            const Text('Where is this bin?', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: _kInk)),
            const SizedBox(height: 4),
            Text(status, style: const TextStyle(color: _kInkMuted)),
            const SizedBox(height: 14),
            if (d.lat != null) AnimatedOpacity(
              opacity: 1, duration: const Duration(milliseconds: 400),
              child: Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(color: _kBgBottom, borderRadius: BorderRadius.circular(12)),
                child: Row(children: [
                  const Icon(Icons.check_circle, color: _kPrimary),
                  const SizedBox(width: 10),
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text('${d.lat!.toStringAsFixed(6)}, ${d.lng!.toStringAsFixed(6)}',
                        style: const TextStyle(fontWeight: FontWeight.w600)),
                    Text('Accuracy: ±${(d.accuracy ?? 0).toStringAsFixed(0)} m', style: const TextStyle(color: _kInkMuted, fontSize: 12)),
                  ])),
                ]),
              ),
            ),
            const SizedBox(height: 16),
            _PrimaryButton(
              label: d.lat != null ? 'Continue' : 'Retry',
              icon: d.lat != null ? Icons.arrow_forward : Icons.refresh,
              busy: busy,
              onTap: d.lat != null ? widget.onNext : _getLocation,
            ),
          ]),
        ),
      ]),
    );
  }
}

/* ============================================================
   STEP 2 — Connect to the bin (SMARTBIN-* AP)
   ============================================================ */
class Step2ConnectBin extends StatefulWidget {
  final ProvData data; final VoidCallback onNext; final VoidCallback onBack;
  const Step2ConnectBin({super.key, required this.data, required this.onNext, required this.onBack});
  @override
  State<Step2ConnectBin> createState() => _Step2State();
}
class _Step2State extends State<Step2ConnectBin> {
  List<WifiNetwork> _bins = [];
  bool scanning = false;
  String status = "Tap Scan to find your bin's hotspot.";
  Timer? _poller;

  @override void dispose() { _poller?.cancel(); super.dispose(); }

  Future<void> _scan() async {
    setState(() { scanning = true; status = 'Scanning...'; _bins = []; });
    await Permission.nearbyWifiDevices.request();
    await Permission.location.request();
    try {
      final list = await WiFiForIoTPlugin.loadWifiList();
      _bins = list.where((n) => (n.ssid ?? '').startsWith('SMARTBIN-')).toList();
      _bins.sort((a, b) => (b.level ?? -100).compareTo(a.level ?? -100));
      status = _bins.isEmpty ? 'No SMARTBIN-* hotspots found yet.' : 'Found ${_bins.length} bin(s). Tap one to connect.';
    } catch (e) { status = 'Scan failed: $e'; }
    setState(() => scanning = false);
  }

  void _startPolling(String wantedSsid) {
    _poller?.cancel();
    _poller = Timer.periodic(const Duration(milliseconds: 1500), (t) async {
      try {
        final s = await WiFiForIoTPlugin.getSSID();
        final cur = (s ?? '').replaceAll('"', '');
        if (cur.startsWith('SMARTBIN-')) {
          await WiFiForIoTPlugin.forceWifiUsage(true);
          widget.data.binSsid = cur;
          t.cancel();
          if (mounted) {
            setState(() => status = 'Connected to $cur. Continuing...');
            Future.delayed(const Duration(milliseconds: 600), widget.onNext);
          }
        }
      } catch (_) {}
    });
  }

    Future<void> _onTapBin(String ssid) async {
    setState(() => status = 'Connecting to $ssid...');
    widget.data.binSsid = ssid;

    // 1) Programmatic auto-connect (Android shows ONE "Use this network?" dialog,
    //    no password prompt because we supply it).
    bool ok = false;
    try {
      ok = await WiFiForIoTPlugin.connect(
        ssid, password: _kBinApPass, security: NetworkSecurity.WPA,
        joinOnce: true, withInternet: false, isHidden: false,
      );
    } catch (_) { ok = false; }

    if (ok) {
      await WiFiForIoTPlugin.forceWifiUsage(true);
      setState(() => status = 'Connected to $ssid.');
      Future.delayed(const Duration(milliseconds: 400), widget.onNext);
      return;
    }

    // 2) Poll: sometimes connect returns false but Android still attaches a beat later
    for (int i = 0; i < 6; i++) {
      await Future.delayed(const Duration(seconds: 1));
      try {
        final s = (await WiFiForIoTPlugin.getSSID() ?? '').replaceAll('"', '');
        if (s.startsWith('SMARTBIN-')) {
          await WiFiForIoTPlugin.forceWifiUsage(true);
          widget.data.binSsid = s;
          setState(() => status = 'Connected to $s.');
          Future.delayed(const Duration(milliseconds: 400), widget.onNext);
          return;
        }
      } catch (_) {}
    }

    // 3) Manual fallback: copy password, prompt user to use system WiFi settings.
    await Clipboard.setData(const ClipboardData(text: _kBinApPass));
    _startPolling(ssid);
    if (!mounted) return;
    setState(() => status = 'Password copied. Open WiFi settings and tap "$ssid".');
  }

  @override
  Widget build(BuildContext c) {
    return SingleChildScrollView(
      child: Column(children: [
        const SizedBox(height: 8),
        _Card(
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            const Text('Connect to the bin', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: _kInk)),
            const SizedBox(height: 4),
            Text(status, style: const TextStyle(color: _kInkMuted)),
            const SizedBox(height: 14),
            _PrimaryButton(label: scanning ? 'Scanning...' : 'Scan for bins', icon: Icons.radar, busy: scanning, onTap: _scan),
          ]),
        ),
        for (final n in _bins) Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
          child: Material(
            color: Colors.white, borderRadius: BorderRadius.circular(14),
            child: InkWell(
              borderRadius: BorderRadius.circular(14),
              onTap: () => _onTapBin(n.ssid ?? ''),
              child: Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(border: Border.all(color: _kBorder), borderRadius: BorderRadius.circular(14)),
                child: Row(children: [
                  Container(
                    width: 44, height: 44,
                    decoration: BoxDecoration(color: _kBgBottom, borderRadius: BorderRadius.circular(10)),
                    child: const Icon(Icons.delete_outline, color: _kPrimary),
                  ),
                  const SizedBox(width: 12),
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(n.ssid ?? '', style: const TextStyle(fontWeight: FontWeight.w600, color: _kInk)),
                    Text('Signal ${n.level ?? '-'} dBm', style: const TextStyle(color: _kInkMuted, fontSize: 12)),
                  ])),
                  _SignalBars(rssi: n.level ?? -90),
                ]),
              ),
            ),
          ),
        ),
        Padding(padding: const EdgeInsets.all(16), child: _GhostButton(label: 'Back', onTap: widget.onBack)),
      ]),
    );
  }
}

/* ============================================================
   STEP 3 — Pick WiFi (scan nearby, exclude SMARTBIN-*)
   ============================================================ */
class Step3WiFi extends StatefulWidget {
  final ProvData data; final VoidCallback onNext; final VoidCallback onBack;
  const Step3WiFi({super.key, required this.data, required this.onNext, required this.onBack});
  @override
  State<Step3WiFi> createState() => _Step3State();
}
class _Step3State extends State<Step3WiFi> {
  List<WifiNetwork> nets = [];
  bool scanning = false;
  String? selected;
  final passCtl = TextEditingController();
  bool showPass = false;

  @override void initState() { super.initState(); _scan(); }

  Future<void> _scan() async {
    setState(() { scanning = true; nets = []; });
    await Permission.nearbyWifiDevices.request();
    await Permission.location.request();
    try {
      final list = await WiFiForIoTPlugin.loadWifiList();
      nets = list.where((n) => (n.ssid ?? '').isNotEmpty && !(n.ssid ?? '').startsWith('SMARTBIN-')).toList();
      // unique by SSID, strongest first
      final byName = <String, WifiNetwork>{};
      for (final n in nets) {
        final k = n.ssid!;
        if (!byName.containsKey(k) || (n.level ?? -100) > (byName[k]!.level ?? -100)) byName[k] = n;
      }
      nets = byName.values.toList()..sort((a, b) => (b.level ?? -100).compareTo(a.level ?? -100));
    } catch (_) {}
    setState(() => scanning = false);
  }

  @override
  Widget build(BuildContext c) {
    return Column(children: [
      Expanded(child: ListView(children: [
        _Card(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: const [
            Icon(Icons.wifi, color: _kPrimary),
            SizedBox(width: 8),
            Text('Choose the WiFi for the bin', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: _kInk)),
          ]),
          const SizedBox(height: 4),
          const Text('Pick a 2.4 GHz network with internet. 5 GHz networks won\'t work with ESP32.',
              style: TextStyle(color: _kInkMuted, fontSize: 12)),
          const SizedBox(height: 12),
          Row(children: [
            Expanded(child: OutlinedButton.icon(onPressed: scanning ? null : _scan,
                icon: scanning
                    ? const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.refresh),
                label: Text(scanning ? 'Scanning…' : 'Rescan'))),
          ]),
        ])),
        for (final n in nets) Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
          child: Material(
            color: Colors.white, borderRadius: BorderRadius.circular(12),
            child: InkWell(
              borderRadius: BorderRadius.circular(12),
              onTap: () => setState(() {
                selected = n.ssid; widget.data.wifiSsid = n.ssid!;
              }),
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 200),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  border: Border.all(color: selected == n.ssid ? _kPrimary : _kBorder, width: selected == n.ssid ? 1.6 : 1),
                  borderRadius: BorderRadius.circular(12),
                  color: selected == n.ssid ? _kBgBottom : Colors.white,
                ),
                child: Row(children: [
                  Icon((n.capabilities ?? '').contains('WPA') || (n.capabilities ?? '').contains('WEP') ? Icons.lock : Icons.lock_open,
                      size: 18, color: _kInkMuted),
                  const SizedBox(width: 10),
                  Expanded(child: Text(n.ssid ?? '', style: const TextStyle(fontWeight: FontWeight.w500, color: _kInk))),
                  _SignalBars(rssi: n.level ?? -90),
                ]),
              ),
            ),
          ),
        ),
        if (selected != null) Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          child: TextField(
            controller: passCtl,
            obscureText: !showPass,
            onChanged: (v) => widget.data.wifiPass = v,
            decoration: InputDecoration(
              labelText: 'Password for "$selected"',
              filled: true, fillColor: Colors.white,
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: const BorderSide(color: _kBorder)),
              suffixIcon: IconButton(
                icon: Icon(showPass ? Icons.visibility_off : Icons.visibility, color: _kInkMuted),
                onPressed: () => setState(() => showPass = !showPass),
              ),
            ),
          ),
        ),
      ])),
      Padding(padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        child: Row(children: [
          _GhostButton(label: 'Back', onTap: widget.onBack),
          const Spacer(),
          SizedBox(width: 200, child: _PrimaryButton(
            label: 'Continue', icon: Icons.arrow_forward,
            onTap: (selected != null) ? widget.onNext : null,
          )),
        ])),
    ]);
  }
}

/* ============================================================
   STEP 4 — Bin details
   ============================================================ */
class Step4Details extends StatefulWidget {
  final ProvData data; final VoidCallback onNext; final VoidCallback onBack;
  const Step4Details({super.key, required this.data, required this.onNext, required this.onBack});
  @override
  State<Step4Details> createState() => _Step4State();
}
class _Step4State extends State<Step4Details> {
  final binName = TextEditingController();
  final ward = TextEditingController();
  final route = TextEditingController();
  final place = TextEditingController();

  bool get _ok =>
      binName.text.isNotEmpty && ward.text.isNotEmpty && route.text.isNotEmpty && place.text.isNotEmpty;

  Widget _f(String label, TextEditingController c, IconData icon) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: TextField(
          controller: c, onChanged: (_) => setState(() {}),
          decoration: InputDecoration(
            labelText: label,
            prefixIcon: Icon(icon, color: _kInkMuted, size: 20),
            filled: true, fillColor: Colors.white,
            border: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: const BorderSide(color: _kBorder)),
          ),
        ),
      );

  @override
  Widget build(BuildContext c) {
    return Column(children: [
      Expanded(child: ListView(children: [
        _Card(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          const Text('Bin details', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: _kInk)),
          const Text('The Bin ID is generated automatically by the device.', style: TextStyle(color: _kInkMuted, fontSize: 12)),
          const SizedBox(height: 8),
          _f('Bin name', binName, Icons.badge_outlined),
          _f('Ward', ward, Icons.location_city),
          _f('Route', route, Icons.route),
          _f('Place / landmark', place, Icons.place_outlined),
        ])),
      ])),
      Padding(padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        child: Row(children: [
          _GhostButton(label: 'Back', onTap: widget.onBack),
          const Spacer(),
          SizedBox(width: 200, child: _PrimaryButton(
            label: 'Review', icon: Icons.arrow_forward,
            onTap: _ok ? () {
              widget.data.binName = binName.text.trim();
              widget.data.ward = ward.text.trim();
              widget.data.route = route.text.trim();
              widget.data.place = place.text.trim();
              widget.onNext();
            } : null,
          )),
        ])),
    ]);
  }
}

/* ============================================================
   STEP 5 — Review + animated Save → Success
   ============================================================ */
class Step5Save extends StatefulWidget {
  final ProvData data;
  final VoidCallback onDone;
  const Step5Save({super.key, required this.data, required this.onDone});
  @override
  State<Step5Save> createState() => _Step5State();
}
class _Step5State extends State<Step5Save> with SingleTickerProviderStateMixin {
  bool sending = false;
  String? error;
  bool done = false;
  late final AnimationController _check = AnimationController(vsync: this, duration: const Duration(milliseconds: 700));

  @override void dispose() { _check.dispose(); super.dispose(); }

  Future<void> _save() async {
    setState(() { sending = true; error = null; });
    final d = widget.data;
    try {
      final resp = await http.post(Uri.parse('http://192.168.4.1/save'), body: {
        'ssid': d.wifiSsid, 'password': d.wifiPass,
        'bin_name': d.binName,
        'ward': d.ward, 'route': d.route, 'place': d.place,
        'lat': d.lat!.toStringAsFixed(6),   'lng': d.lng!.toStringAsFixed(6),
        'latitude': d.lat!.toStringAsFixed(6), 'longitude': d.lng!.toStringAsFixed(6),
      }).timeout(const Duration(seconds: 15));
      await WiFiForIoTPlugin.forceWifiUsage(false);
      if (resp.statusCode >= 200 && resp.statusCode < 300) {
        setState(() { sending = false; done = true; });
        _check.forward();
        Future.delayed(const Duration(seconds: 3), () {
          if (mounted) widget.onDone();
        });
      } else {
        setState(() { sending = false; error = 'Device replied HTTP ${resp.statusCode}.'; });
      }
    } catch (e) {
      setState(() { sending = false; error = '$e\nIs the phone still on the SMARTBIN hotspot?'; });
    }
  }

  Widget _row(String k, String v) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(children: [
          SizedBox(width: 110, child: Text(k, style: const TextStyle(color: _kInkMuted, fontSize: 13))),
          Expanded(child: Text(v, style: const TextStyle(fontWeight: FontWeight.w500, color: _kInk))),
        ]),
      );

  @override
  Widget build(BuildContext c) {
    final d = widget.data;
    if (done) {
      return const PopScope(
        canPop: false,
        child: _Success(thing: 'this bin'),
      );
    }
    return SingleChildScrollView(child: Column(children: [
      _Card(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        const Text('Review', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: _kInk)),
        const SizedBox(height: 8),
        _row('Bin', d.binName.isEmpty ? 'auto from device' : '${d.binName} (Bin ID auto from device)'),
        _row('Location', '${d.place}, ${d.ward}'),
        _row('Route', d.route),
        _row('GPS', '${d.lat?.toStringAsFixed(6)}, ${d.lng?.toStringAsFixed(6)}'),
        _row('WiFi', d.wifiSsid),
        _row('Bin hotspot', d.binSsid ?? '—'),
      ])),
      if (error != null) Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        child: Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(color: _kDanger.withOpacity(0.08), border: Border.all(color: _kDanger.withOpacity(0.3)), borderRadius: BorderRadius.circular(12)),
          child: Text(error!, style: const TextStyle(color: _kDanger, fontSize: 13)),
        ),
      ),
      Padding(padding: const EdgeInsets.all(16),
        child: Align(
          alignment: Alignment.centerRight,
          child: SizedBox(width: 220, child: _PrimaryButton(
            label: sending ? 'Sending…' : 'Save & Provision',
            icon: Icons.cloud_upload_outlined,
            busy: sending, onTap: _save,
          )),
        )),
    ]));
  }
}

class _Success extends StatefulWidget {
  final String thing;
  const _Success({super.key, required this.thing});
  @override
  State<_Success> createState() => _SuccessState();
}
class _SuccessState extends State<_Success> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 700))..forward();
  @override void dispose() { _c.dispose(); super.dispose(); }
  @override
  Widget build(BuildContext context) {
    return Center(child: SingleChildScrollView(child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        AnimatedBuilder(
          animation: _c,
          builder: (_, __) => Transform.scale(
            scale: Curves.elasticOut.transform(_c.value),
            child: Container(
              width: 120, height: 120,
              decoration: const BoxDecoration(color: _kPrimary, shape: BoxShape.circle),
              child: const Icon(Icons.check, color: Colors.white, size: 76),
            ),
          ),
        ),
        const SizedBox(height: 24),
        const Text('All set!', style: TextStyle(fontSize: 26, fontWeight: FontWeight.w700, color: _kInk)),
        const SizedBox(height: 6),
        Text('The bin (${widget.thing}) is connecting to WiFi and onboarding to AWS now.\nIt will appear live on your dashboard within a minute.',
            textAlign: TextAlign.center, style: const TextStyle(color: _kInkMuted, height: 1.4)),
      ]),
    )));
  }
}

class _SignalBars extends StatelessWidget {
  final int rssi;

  const _SignalBars({required this.rssi});

  @override
  Widget build(BuildContext context) {
    int bars;

    if (rssi >= -55) {
      bars = 4;
    } else if (rssi >= -67) {
      bars = 3;
    } else if (rssi >= -75) {
      bars = 2;
    } else if (rssi >= -85) {
      bars = 1;
    } else {
      bars = 0;
    }

    return Row(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.end,
      children: List.generate(4, (index) {
        final active = index < bars;

        return Container(
          margin: const EdgeInsets.symmetric(horizontal: 1),
          width: 4,
          height: 6 + (index * 4),
          decoration: BoxDecoration(
            color: active ? _kPrimary : _kBorder,
            borderRadius: BorderRadius.circular(2),
          ),
        );
      }),
    );
  }
}
