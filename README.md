# Camera Radar

Two-phone motion radar that runs in the browser.

- **Radar phone**: uses the rear camera (looks ahead) and front camera (looks behind), detects motion and recognises people, animals and vehicles with an on-device AI model (COCO-SSD).
- **Screen phone**: shows a 360° radar, alerts, a contact list and a detection log, and controls the radar phone.

The phones pair with a 6-digit code (or QR code) and talk over WebRTC, so data goes directly across the hotspot.

Open `index.html` over HTTPS (GitHub Pages works). The radar phone needs mobile data on for its hotspot, since pairing and the AI model download go through the internet.
