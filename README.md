# Cozy Switch

A small Android app and home-screen widget for switching CozyLife Wi-Fi plugs on and off,
without the old CozyLife app.

- Set the plugs up once in the CozyLife app (that's where the Bluetooth pairing happens).
- Open Cozy Switch on the same Wi-Fi and tap **Find plugs**.
- Add the **Cozy Switch plugs** widget and tap a plug to switch it.

The app talks to the plugs directly over your home network (TCP port 5555, the same local
protocol the Home Assistant CozyLife integration uses). No account or cloud is involved,
so it works while the phone is on the same Wi-Fi as the plugs.

Every push to `main` runs the protocol tests, builds a signed APK and publishes it under
Releases. The app checks Releases and updates itself.
