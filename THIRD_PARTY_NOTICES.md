# Third-party notices

## QQMusicApi

The QQ Music `zzc` request signature in
`app/src/main/java/com/lyricsplus/android/lyrics/QqMusicRequestSigner.kt`
is based on the protocol implementation in
[L-1124/QQMusicApi](https://github.com/L-1124/QQMusicApi/blob/27861e51432ea6b6e88dc35c4e8c8e239c0339e8/qqmusic_api/algorithms/sign.py).
The signed gateway request format is documented in that project's
`qqmusic_api/core/executor.py`.

QQMusicApi is distributed under the GNU General Public License version 3.
This repository also includes that license in `LICENSE`.

## QQMusicDecoder

The QQ Music QRC DES routines in
`app/src/main/java/com/lyricsplus/android/lyrics/QqMusicQrcDecryptor.kt` are
adapted from [WXRIW/QQMusicDecoder](https://github.com/WXRIW/QQMusicDecoder).

MIT License

Copyright (c) 2023 WXRIW

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
