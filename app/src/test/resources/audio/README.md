`meeting-en.wav` is a synthetic, single-speaker test fixture generated with the local FFmpeg/libflite `slt` voice:

```
ffmpeg -f lavfi -i "flite=text='The meeting starts at nine. The budget is four million euros.':voice=slt" -ar 16000 -ac 1 -c:a pcm_s16le meeting-en.wav
```

It contains no real meeting audio. Live gateway tests are opt-in and use `OPENROUTER_API_KEY` only at test runtime. The regular unit suite does not call OpenRouter.
