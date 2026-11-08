`meeting-en.wav` is a synthetic, single-speaker test fixture generated with the local FFmpeg/libflite `slt` voice:

```
ffmpeg -f lavfi -i "flite=text='The meeting starts at nine. The budget is four million euros.':voice=slt" -ar 16000 -ac 1 -c:a pcm_s16le meeting-en.wav
```

It contains no real meeting audio. Live gateway tests are opt-in and use `OPENROUTER_API_KEY` only at test runtime. The regular unit suite does not call OpenRouter.


`meeting-de.wav` is a synthetic German single-speaker fixture generated locally with eSpeak NG 1.52.0 (Debian trixie package), then resampled with FFmpeg to 16 kHz mono PCM16:

```bash
espeak-ng -v de -s 155 -w meeting-de-source.wav 'Die Besprechung beginnt um neun Uhr. Das Budget beträgt vier Millionen Euro.'
ffmpeg -i meeting-de-source.wav -ar 16000 -ac 1 -c:a pcm_s16le meeting-de.wav
```

The opt-in gateway and production-client tests cover both languages with MAI-Transcribe 2 and Voxtral Mini Transcribe 3. No real meeting audio is included.
