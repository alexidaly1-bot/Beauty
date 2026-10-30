# Soft Face

Live soft-makeup camera for the Galaxy Z Fold6 (Kotlin, CameraX, MediaPipe, OpenGL ES 3).

- Makes up **only your face** (face lock). Other people in the shot are left alone.
- Everything runs on the phone. The app has **no internet permission**.
- Every push builds an APK: see **Releases** (`softface-N`).

## What it does

| Slider | Effect |
|---|---|
| Overall | Strength of everything (0–100%) |
| Smooth skin | Edge-aware skin smoothing |
| Contour | Soft shade on cheek hollows, jaw, nose sides, temples |
| Blush | Rosy glow on the apples of the cheeks |
| Lips | Sheer rose tint that keeps lip texture |
| Bright eyes | Lifts brightness around the eyes |
| Slim face | Gentle jaw/cheek warp |
| Line up | Left/right, up/down, size, or drag and pinch on the picture |

Reset button, 3 built-in looks (Natural, Soft glam, Just skin) and your own saved looks.
Sliders sit in a panel that hides with one tap (side panel on the inner screen, bottom panel on the cover screen).

## How it works

1. CameraX streams RGBA frames (about 1440×1080).
2. A small upright copy goes to **MediaPipe Face Landmarker** (478 points, up to 4 faces, GPU).
3. **Face lock**: each face is tracked and checked with **MobileFaceNet** (128-number face print, TFLite).
   Set-up takes 5 photos from different angles; only the numbers are saved (`files/face_lock.json`), never photos.
4. The same frame is drawn on the GPU: blur pass, a face mask (skin / lips / eyes), then one composite shader.
   Because the landmarks come from the same frame, the makeup never lags behind when you move.
5. Photos and videos (H.264 + AAC) save to Gallery > Soft Face, with the look.

## Using it as "the camera" in other apps (no root)

Works:
- Side key double press > Soft Face (Samsung setting).
- Apps that send a "take photo / record video" request and let you choose an app.
- Take it in Soft Face, then share.

Not possible without root:
- Live effects inside apps that run their own camera (WhatsApp, Instagram, Snapchat, TikTok, Zoom, Teams).
  Since Android 11, photo/video requests that don't show a chooser only go to the built-in Samsung Camera.
- A virtual camera other apps can see (system-only permission).
- Plugging into Samsung "Video call effects".

## Models

- `face_landmarker.task`: Google MediaPipe (Apache 2.0).
- `mobile_facenet.tflite`: MobileFaceNet from Qualcomm AI Hub (Apache 2.0).
- Font: Atkinson Hyperlegible (SIL OFL).
