(function () {
  var lyricsEl = document.getElementById("lyrics");
  var emptyEl = document.getElementById("empty");
  var stageEl = document.getElementById("stage");
  var lyricsViewportEl = null;

  function getScrollContainer() {
    if (!lyricsViewportEl) {
      lyricsViewportEl = document.querySelector(".lyrics-viewport") || stageEl;
    }
    return state.isFullLyricsMode ? lyricsViewportEl : stageEl;
  }
  var trackTitleEl = document.getElementById("trackTitle");
  var trackArtistEl = document.getElementById("trackArtist");
  var vrrKeepaliveEl = document.getElementById("vrr-keepalive");

  var state = {
    track: null,
    lyrics: [],
    activeIndex: -1,
    readingMode: 1, // 0=None, 1=Romaji, 2=Furigana
    isFullLyricsMode: false
  };

  var playback = {
    positionMs: 0,
    isPlaying: false,
    updatedAt: 0,
    visualOffset: 0,
    resumeTime: 0
  };

  var prevActiveIndex = -1;
  var cachedActiveSyllables = [];

  function recacheActiveSyllables() {
    // Reset any .syllable.active not on the current active line (old line that just scrolled up)
    var allActiveSyllables = lyricsEl.querySelectorAll(".syllable.active");
    var currentActiveLine = lyricsEl.querySelector(".line.active");
    for (var i = 0; i < allActiveSyllables.length; i++) {
      var el = allActiveSyllables[i];
      if (!currentActiveLine || !currentActiveLine.contains(el)) {
        el.className = "syllable";
      }
    }
    cachedActiveSyllables = [];
    var activeLineEl = lyricsEl.querySelector(".line.active");
    if (!activeLineEl) return;
    
    var syllables = activeLineEl.querySelectorAll(".syllable");
    for (var i = 0; i < syllables.length; i++) {
      var el = syllables[i];
      cachedActiveSyllables.push({
        el: el,
        start: Number(el.getAttribute("data-start")) || 0,
        end: Number(el.getAttribute("data-end")) || 0,
        lastClass: "",
        lastProgress: -1
      });
    }
  }

  // --- User scroll/browse state (landscape lyrics pane) ---
  var userScrolling = false;
  var scrollOffset = 0;
  var touchStartY = 0;
  var scrollIdleTimer = null;
  var wasScrollGesture = false; // suppress click after scroll
  var scrollVelocity = 0;
  var lastMoveTime = 0;
  var lastTouchTime = 0; // timestamp of last user touch, for stuck-detection
  var flingRafId = null;
  var SCROLL_RESUME_DELAY = 1000; // 1s idle → resume auto-follow
  var RECOVERY_TIMEOUT = 2000; // force recovery if userScrolling stuck for 2s
  var scrollBoundaries = null; // { minScroll: number, maxScroll: number }

  function forceRecover() {
    if (!userScrolling) return;
    scrollBoundaries = null;
    userScrolling = false;
    wasScrollGesture = false;
    if (flingRafId !== null) { cancelAnimationFrame(flingRafId); flingRafId = null; }
    if (scrollIdleTimer !== null) { clearTimeout(scrollIdleTimer); scrollIdleTimer = null; }
    stageEl.classList.remove("browsing");
    lyricsEl.style.transition = "";
    // Snap to current playback position
    var lines = lyricsEl.querySelectorAll(".line");
    if (lines[state.activeIndex]) {
      var activeLine = lines[state.activeIndex];
      var containerHeight = getScrollContainer().clientHeight;
      var cs = getComputedStyle(lyricsEl);
      var padTop = parseFloat(cs.paddingTop) || 0;
      var anchor = containerHeight * 0.33;
      lyricsEl.style.transform = "translateY(" + (anchor - padTop - activeLine.offsetTop) + "px)";
    }
    updatePlaybackPosition();
  }

  function getScrollBoundaries() {
    var lines = lyricsEl.querySelectorAll(".line");
    if (!lines.length) return { minScroll: 0, maxScroll: 0 };
    var containerHeight = getScrollContainer().clientHeight;
    var cs = getComputedStyle(lyricsEl);
    var padTop = parseFloat(cs.paddingTop) || 0;
    var anchor = containerHeight * 0.33;
    var firstLine = lines[0];
    var lastLine = lines[lines.length - 1];
    var minScroll = firstLine.offsetTop + padTop - anchor;
    var maxScroll = lastLine.offsetTop + lastLine.clientHeight + padTop - anchor;
    return { minScroll: Math.round(Math.min(minScroll, 0)), maxScroll: Math.round(Math.max(maxScroll, 0)) };
  }

  function clampWithRubberBand(offset, min, max) {
    var STRENGTH = 0.35;
    if (offset < min) return min - (min - offset) * STRENGTH;
    if (offset > max) return max + (offset - max) * STRENGTH;
    return offset;
  }

  function report(message) {
    try {
      var text = String(message);
      var isError = text.indexOf("error:") === 0;
      var shouldReport = !!window.LYRICS_PLUS_DEBUG || isError;
      if (!shouldReport) return;
      if (window.LYRICS_PLUS_DEBUG && window.console && window.console.log) {
        window.console.log("LyricsPlus " + message);
      }
      if (window.AndroidLyrics && window.AndroidLyrics.report) {
        window.AndroidLyrics.report(text);
      }
    } catch (ignore) {
    }
  }

  function hashColor(seed, sat, light) {
    var hash = 0;
    for (var i = 0; i < seed.length; i += 1) {
      hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
    }
    return "hsl(" + (hash % 360) + " " + sat + "% " + light + "%)";
  }

  function escapeHtml(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function setTrack(track) {
    try {
      var seed = String((track && track.track) || "") + String((track && track.artist) || "");
      var startColor = (track && track.backgroundStart) || hashColor(seed || "lyrics", 68, 48);
      var endColor = (track && track.backgroundEnd) || hashColor(seed + " deep", 42, 24);
      var accentColor = (track && track.backgroundAccent) || hashColor(seed + " accent", 74, 62);
      state.track = track || null;
      trackTitleEl.textContent = (track && track.track) || "Lyrics Plus";
      trackArtistEl.textContent = (track && track.artist) || "等待 Spotify";
      stageEl.style.setProperty("--bg-a", startColor);
      stageEl.style.setProperty("--bg-b", endColor);
      stageEl.style.setProperty("--bg-c", accentColor);
      stageEl.setAttribute("data-track-key", seed);
      report("track: " + seed + ", colors: " + startColor + " -> " + endColor);
    } catch (error) {
      report("error:设置歌曲信息失败:" + error.message);
    }
  }

  function setLyrics(lines) {
    try {
      scrollBoundaries = null;
      state.lyrics = Object.prototype.toString.call(lines) === "[object Array]" ? lines : [];
      if (state.lyrics.length > 0) {
        var firstLineStart = Number(state.lyrics[0].startTimeMs || 0);
        if (firstLineStart > 2000) {
          state.lyrics.unshift({
            startTimeMs: 0,
            text: "•••",
            translation: "",
            reading: ""
          });
        }
      }
      emptyEl.hidden = state.lyrics.length > 0;
      if (state.lyrics.length > 0) {
        var elapsed = playback.isPlaying ? (performance.now() - playback.updatedAt) : 0;
        var currentPosition = playback.positionMs + elapsed;
        state.activeIndex = findActiveIndex(currentPosition);
      } else {
        state.activeIndex = -1;
      }
      // Reset scroll position when new lyrics arrive (e.g. track change)
      if (state.isFullLyricsMode) {
        getScrollContainer().scrollTop = 0;
      }
      prevActiveIndex = -1;
      // Full rebuild: new lyrics data means new DOM
      renderFull();
      report("lyrics: " + state.lyrics.length + ", active: " + state.activeIndex);
    } catch (error) {
      report("error:设置歌词失败:" + error.message);
    }
  }

  function setPlaybackState(positionMs, isPlaying) {
    try {
      var wasPlaying = playback.isPlaying;
      var newIsPlaying = !!isPlaying;
      var targetPos = Number(positionMs) || 0;

      if (newIsPlaying) {
        if (!wasPlaying) {
          // Transition from paused to playing: smoothly transition/decay the visual offset
          var frozenPos = playback.positionMs;
          var diff = frozenPos - targetPos;
          if (Math.abs(diff) < 1500) {
            playback.visualOffset = diff;
          } else {
            playback.visualOffset = 0;
          }
          playback.positionMs = targetPos;
          playback.resumeTime = performance.now();
        } else {
          // Already playing: periodic sync update
          playback.positionMs = targetPos;
        }
      } else {
        if (wasPlaying) {
          // Transition from playing to paused: freeze visually at the extrapolated position
          var elapsed = performance.now() - playback.updatedAt;
          var extrapolated = playback.positionMs + elapsed;
          if (Math.abs(extrapolated - targetPos) < 1500) {
            playback.positionMs = extrapolated;
          } else {
            playback.positionMs = targetPos;
          }
          playback.visualOffset = 0;
        } else {
          // Already paused: ignore minor position updates (such as duplicate pause reports from Android)
          // only accept seeks (changes > 1.5s)
          var diff = Math.abs(playback.positionMs - targetPos);
          if (diff > 1500) {
            playback.positionMs = targetPos;
            playback.visualOffset = 0;
          } else {
            // Keep the frozen position, ignore the minor update
          }
        }
      }

      playback.isPlaying = newIsPlaying;
      playback.updatedAt = performance.now();

      if (playback.isPlaying) {
        stageEl.classList.remove("paused");
        startTick();
      } else {
        stageEl.classList.add("paused");
        stopTick();
      }
      updatePlaybackPosition();
    } catch (error) {
      report("error:同步播放状态失败:" + error.message);
    }
  }

  function setReadingMode(mode) {
    try {
      state.readingMode = Number(mode) || 0; // 0=None, 1=Romaji, 2=Furigana
      // Mode change requires DOM content changes, so do a full render
      renderFull();
      report("setReadingMode: " + state.readingMode);
    } catch (error) {
      report("error:设置注音模式失败:" + error.message);
    }
  }

  var _isRightAligned = false;

  function setRightAligned(rightAligned) {
    try {
      var changed = _isRightAligned !== !!rightAligned;
      _isRightAligned = !!rightAligned;
      if (rightAligned) {
        stageEl.classList.add("right-aligned");
      } else {
        stageEl.classList.remove("right-aligned");
      }
      // On orientation change, fully rebuild DOM so line visibility is correct
      if (changed && state.lyrics.length > 0) {
        prevActiveIndex = -1;
        // Disable transition to prevent jarring animation
        lyricsEl.style.transition = "none";
        renderFull();
        requestAnimationFrame(function () {
          requestAnimationFrame(function () {
            lyricsEl.style.transition = "";
          });
        });
      }
      report("setRightAligned: " + rightAligned);
    } catch (error) {
      report("error:设置横屏布局失败:" + error.message);
    }
  }


  function updatePlaybackPosition() {
    if (!state.lyrics.length) return;
    var elapsed = playback.isPlaying ? (performance.now() - playback.updatedAt) : 0;
    var currentPosition = playback.positionMs + elapsed;

    // Apply smooth visual offset decay if resuming
    if (playback.isPlaying && playback.visualOffset) {
      var decayTime = 1000; // Decay the visual offset to 0 over 1 second
      var timeSinceResume = performance.now() - playback.resumeTime;
      if (timeSinceResume < decayTime) {
        var ratio = 1 - (timeSinceResume / decayTime);
        ratio = ratio * ratio; // quadratic ease-out
        currentPosition += playback.visualOffset * ratio;
      } else {
        playback.visualOffset = 0;
      }
    }

    var nextIndex = findActiveIndex(currentPosition);
    if (nextIndex !== state.activeIndex) {
      state.activeIndex = nextIndex;
      if (state.isFullLyricsMode) {
        // Lightweight update: just swap CSS classes, no DOM re-render
        updateActiveClassesOnly();
      } else {
        // Incremental update: patch styles and classes, no DOM rebuild
        patchFocusedMode();
      }
      recacheActiveSyllables();
      report("playback: " + Math.round(currentPosition / 1000) + "s, active: " + state.activeIndex);
    }
    updateSyllableHighlights(currentPosition);
  }

  function updateSyllableHighlights(currentPosition) {
    var syllables = cachedActiveSyllables;
    if (!syllables.length) return;
    
    for (var i = 0; i < syllables.length; i++) {
      var item = syllables[i];
      var el = item.el;
      var start = item.start;
      var end = item.end;
      
      var targetClass = "syllable";
      var progress = 0;
      
      if (currentPosition >= end) {
        // Keep as active with 100% progress so it stays white until whole line finishes
        targetClass = "syllable active";
        progress = 100;
      } else if (currentPosition >= start && currentPosition < end) {
        targetClass = "syllable active";
        var duration = end - start;
        progress = duration > 0 ? Math.round(((currentPosition - start) / duration) * 100) : 100;
      } else {
        targetClass = "syllable";
        progress = 0;
      }

      // Write class name only when it changes
      if (item.lastClass !== targetClass) {
        el.className = targetClass;
        item.lastClass = targetClass;
      }

      // Write CSS property only when progress changes
      if (item.lastProgress !== progress) {
        el.style.setProperty("--progress", progress + "%");
        item.lastProgress = progress;
      }
    }
  }

  var vrrToggle = false;
  var rafId = null;
  function startTick() {
    if (rafId !== null) return;
    function tick() {
      if (playback.isPlaying) {
        updatePlaybackPosition();
        // Stuck-detection: if userScrolling is true but no touch for 5s, force recovery
        if (userScrolling && lastTouchTime > 0 && performance.now() - lastTouchTime > RECOVERY_TIMEOUT) {
          forceRecover();
        }
        // Force 120Hz compositor scheduling on VRR Android screens.
        // Two signals are needed to convince the WebView compositor every frame has work:
        //   1. transform (compositor-only, no layout/paint) — matched by will-change:transform
        //   2. opacity micro-alternation (pixel-level change) — forces actual pixel diff
        // Together they prevent the WebView from throttling RAF to 60Hz.
        if (vrrKeepaliveEl) {
          vrrToggle = !vrrToggle;
          vrrKeepaliveEl.style.transform = vrrToggle ? "translateX(0.5px)" : "translateX(0px)";
          vrrKeepaliveEl.style.opacity = vrrToggle ? "0.015" : "0.01";
        }
      }
      rafId = requestAnimationFrame(tick);
    }
    rafId = requestAnimationFrame(tick);
  }
  function stopTick() {
    if (rafId !== null) {
      cancelAnimationFrame(rafId);
      rafId = null;
    }
  }
  // Start the tick loop if already playing (e.g. WebView loaded mid-track)
  if (playback.isPlaying) { startTick(); }

  function findActiveIndex(positionMs) {
    var lyrics = state.lyrics;
    var len = lyrics.length;
    if (len === 0) return -1;
    if (positionMs < Number(lyrics[0].startTimeMs || 0)) return 0;

    var low = 0;
    var high = len - 1;
    var result = 0;

    while (low <= high) {
      var mid = (low + high) >> 1;
      var midTime = Number(lyrics[mid].startTimeMs || 0);

      if (positionMs >= midTime) {
        result = mid;
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    return result;
  }

  function clampedActiveIndex() {
    if (!state.lyrics.length) return -1;
    if (state.activeIndex < 0) return 0;
    if (state.activeIndex >= state.lyrics.length) return state.lyrics.length - 1;
    return state.activeIndex;
  }

  // ---------- Focused-mode helpers ----------

  function getDistanceStyle(distance) {
    // Only keep up to 2 past lines visible above active
    if (distance < -2) return "opacity:0;transform:scale(0.78)";
    var absDist = Math.abs(distance);
    if (absDist === 0) return "opacity:1;transform:scale(1)";
    // Progressive blur: 2px at dist 1 → 16px at dist 8+
    // opacity: 0.55 at dist 1 → 0.08 at dist 8+
    // scale: 0.92 at dist 1 → 0.75 at dist 8+
    var blurPx = Math.min(absDist * 0.5, 4);
    var opacity = Math.max(0.55 - (absDist - 1) * 0.065, 0.08);
    var scale = Math.max(0.92 - (absDist - 1) * 0.025, 0.75);
    return "opacity:" + opacity + ";transform:scale(" + scale + ");filter:blur(" + blurPx + "px)";
  }

  function getKindClass(distance) {
    if (distance === 0) return "active";
    if (Math.abs(distance) === 1) return "near";
    return "far";
  }

  function getDirectionClass(distance) {
    if (distance < 0) return "past";
    if (distance > 0) return "future";
    return "";
  }

  function getLengthClass(line) {
    var textLen = (line.text || "").length;
    var transLen = (line.translation || "").length;
    var totalLen = textLen + transLen * 0.7;
    return totalLen > 65 ? "long" : totalLen < 12 ? "short" : "";
  }

  function parseSyllables(lineText, lineStartTime) {
    var syllables = [];
    lineText = lineText || "";
    lineStartTime = Number(lineStartTime) || 0;

    // 1. Enhanced LRC: <00:12.34>word
    var lrcRegex = /<(\d{2}):(\d{2})[.:](\d{2,3})>([^<]*)/g;

    // 2. NetEase YRC prefix or QRC absolute: (12580,250,0)word or (19538,207)word
    var prefixRegex = /\((\d+),(\d+)(?:,\d+)?\)([^(<]+)/g;

    // 3. QQ Music YRC/QRC suffix: word(293,293) or word (19538,207)
    // NOTE: We exclude ')' and '(' and '<' from the character class to prevent matching timing tag boundaries.
    var suffixRegex = /([^(<)]+)\s*\((\d+),(\d+)\)/g;

    var match;

    if (lrcRegex.test(lineText)) {
      lrcRegex.lastIndex = 0;
      while ((match = lrcRegex.exec(lineText)) !== null) {
        var min = parseInt(match[1], 10);
        var sec = parseInt(match[2], 10);
        var fracStr = match[3];
        var text = match[4];

        var frac = parseInt(fracStr, 10);
        if (fracStr.length === 2) frac *= 10;

        var timeMs = min * 60000 + sec * 1000 + frac;
        syllables.push({
          timeMs: timeMs,
          text: text
        });
      }
    } else {
      // Robust format detection: if the line starts with a parenthesized timing tag, e.g. (12580,250), it is prefix format.
      // Otherwise, it is suffix format (QQ Music standard YRC/QRC).
      var isPrefix = /^\s*\(\d+,\d+(?:,\d+)?\)/.test(lineText);
      if (isPrefix) {
        prefixRegex.lastIndex = 0;
        while ((match = prefixRegex.exec(lineText)) !== null) {
          var startMs = parseInt(match[1], 10);
          var durationMs = parseInt(match[2], 10);
          var text = match[3];

          // Resolve absolute vs relative (bulletproof check to prevent minor QRC timestamp offsets from being misidentified as relative)
          var isRelative = (startMs < lineStartTime) && (startMs < 1000 || (lineStartTime - startMs) > Math.max(2000, lineStartTime * 0.5));
          var timeMs = isRelative ? (lineStartTime + startMs) : startMs;
          syllables.push({
            timeMs: timeMs,
            durationMs: durationMs,
            text: text
          });
        }
      } else {
        suffixRegex.lastIndex = 0;
        while ((match = suffixRegex.exec(lineText)) !== null) {
          var text = match[1];
          var startMs = parseInt(match[2], 10);
          var durationMs = parseInt(match[3], 10);

          // Resolve absolute vs relative (bulletproof check to prevent minor QRC timestamp offsets from being misidentified as relative)
          var isRelative = (startMs < lineStartTime) && (startMs < 1000 || (lineStartTime - startMs) > Math.max(2000, lineStartTime * 0.5));
          var timeMs = isRelative ? (lineStartTime + startMs) : startMs;
          syllables.push({
            timeMs: timeMs,
            durationMs: durationMs,
            text: text
          });
        }
      }
    }

    return syllables;
  }

  function stripSyllableTimestamps(lineText) {
    if (!lineText) return "";
    return lineText
      .replace(/<(\d{2}):(\d{2})[.:](\d{2,3})>/g, "")
      .replace(/\(\d+,\d+(?:,\d+)?\)/g, "");
  }

  function buildFuriganaInnerHTML(furiganaHtml, syllables, lineDuration, lineStartTime, isPast) {
    if (!furiganaHtml) return "";
    var tokens = furiganaHtml.match(/<ruby>.*?<\/ruby>|[^<>]/g) || [];
    if (syllables.length === 0) return furiganaHtml;

    var html = "";
    var className = isPast ? "syllable past" : "syllable";
    var progressStyle = isPast ? ' style="--progress: 100%"' : '';

    for (var i = 0; i < tokens.length; i++) {
      var sIdx = Math.floor(i * syllables.length / tokens.length);
      if (sIdx >= syllables.length) sIdx = syllables.length - 1;

      var s = syllables[sIdx];

      var nextSIdx = Math.floor((i + 1) * syllables.length / tokens.length);
      if (nextSIdx >= syllables.length) nextSIdx = syllables.length;
      var nextS = syllables[nextSIdx];

      var sEnd = s.durationMs ? (s.timeMs + s.durationMs) : (nextS ? nextS.timeMs : (lineStartTime + lineDuration));
      if (!s.durationMs && !nextS) {
        sEnd = s.timeMs + Math.min(lineStartTime + lineDuration - s.timeMs, 1000);
      }
      if (sEnd <= s.timeMs) {
        sEnd = s.timeMs + 200;
      }

      html += '<span class="' + className + '" data-start="' + s.timeMs + '" data-end="' + sEnd + '"' + progressStyle + '>' + tokens[i] + '</span>';
    }
    return html;
  }

  function buildRomajiInnerHTML(romajiText, lineStartTime, nextLine, mainSyllables, isPast) {
    if (!romajiText) return "";
    // If the Romaji text only contains timing tags and no actual letters/words, discard it
    var clean = romajiText.replace(/\(\d+,\d+(?:,\d+)?\)/g, "").trim();
    if (!clean) return "";

    var romajiSyllables = parseSyllables(romajiText, lineStartTime);
    var html = "";
    var className = isPast ? "syllable past" : "syllable";
    var progressStyle = isPast ? ' style="--progress: 100%"' : '';

    if (romajiSyllables.length > 0) {
      var lineDuration = nextLine ? (nextLine.startTimeMs - lineStartTime) : 4000;
      for (var sIdx = 0; sIdx < romajiSyllables.length; sIdx++) {
        var s = romajiSyllables[sIdx];
        var nextS = romajiSyllables[sIdx + 1];
        var sEnd = s.durationMs ? (s.timeMs + s.durationMs) : (nextS ? nextS.timeMs : (lineStartTime + lineDuration));
        if (!s.durationMs && !nextS) {
          sEnd = s.timeMs + Math.min(lineStartTime + lineDuration - s.timeMs, 1000);
        }
        if (sEnd <= s.timeMs) {
          sEnd = s.timeMs + 200;
        }
        var spacer = sIdx === romajiSyllables.length - 1 ? "" : " ";
        html += '<span class="' + className + '" data-start="' + s.timeMs + '" data-end="' + sEnd + '"' + progressStyle + '>' + escapeHtml(s.text) + '</span>' + spacer;
      }
    } else if (mainSyllables && mainSyllables.length > 0) {
      var tokens = romajiText.trim().split(/\s+/);
      var lineDuration = nextLine ? (nextLine.startTimeMs - lineStartTime) : 4000;
      for (var i = 0; i < tokens.length; i++) {
        var sIdx = Math.floor(i * mainSyllables.length / tokens.length);
        if (sIdx >= mainSyllables.length) sIdx = mainSyllables.length - 1;
        var s = mainSyllables[sIdx];

        var nextSIdx = Math.floor((i + 1) * mainSyllables.length / tokens.length);
        if (nextSIdx >= mainSyllables.length) nextSIdx = mainSyllables.length;
        var nextS = mainSyllables[nextSIdx];

        var sEnd = s.durationMs ? (s.timeMs + s.durationMs) : (nextS ? nextS.timeMs : (lineStartTime + lineDuration));
        if (!s.durationMs && !nextS) {
          sEnd = s.timeMs + Math.min(lineStartTime + lineDuration - s.timeMs, 1000);
        }
        if (sEnd <= s.timeMs) {
          sEnd = s.timeMs + 200;
        }

        var spacer = i === tokens.length - 1 ? "" : " ";
        html += '<span class="' + className + '" data-start="' + s.timeMs + '" data-end="' + sEnd + '"' + progressStyle + '>' + escapeHtml(tokens[i]) + '</span>' + spacer;
      }
    } else {
      html = escapeHtml(romajiText);
    }
    return html;
  }

  function buildLineInnerHTML(line, isActive, nextLine, isPast) {
    var parsedReading = null;
    if (line.reading) {
      try {
        if (line.reading.startsWith("{")) {
          parsedReading = JSON.parse(line.reading);
        }
      } catch (e) {}
    }

    var romajiText = "";
    var furiganaHtml = "";
    if (parsedReading) {
      romajiText = parsedReading.romaji || "";
      furiganaHtml = parsedReading.furigana || "";
    } else if (line.reading) {
      romajiText = line.reading;
    }

    var syllables = parseSyllables(line.text || "", Number(line.startTimeMs || 0));
    var lineTextHtml = "";
    var lineDuration = nextLine ? (nextLine.startTimeMs - line.startTimeMs) : 4000;

    var showFurigana = (state.readingMode === 2 && furiganaHtml && (state.isFullLyricsMode || isActive));

    if (showFurigana) {
      // 1. Furigana mode: Use raw HTML containing <ruby> tags (with word-level sweeps if matched)
      lineTextHtml = buildFuriganaInnerHTML(furiganaHtml, syllables, lineDuration, Number(line.startTimeMs || 0), isPast);
    } else if (syllables.length > 0) {
      // 2. Karaoke mode: Syllable-level enhanced LRC highlights
      var className = isPast ? "syllable past" : "syllable";
      var progressStyle = isPast ? ' style="--progress: 100%"' : '';
      for (var sIdx = 0; sIdx < syllables.length; sIdx++) {
        var s = syllables[sIdx];
        var nextS = syllables[sIdx + 1];
        var sEnd = s.durationMs ? (s.timeMs + s.durationMs) : (nextS ? nextS.timeMs : (line.startTimeMs + lineDuration));
        if (!s.durationMs && !nextS) {
          sEnd = s.timeMs + Math.min(line.startTimeMs + lineDuration - s.timeMs, 1000);
        }
        if (sEnd <= s.timeMs) {
          sEnd = s.timeMs + 200;
        }
        lineTextHtml += '<span class="' + className + '" data-start="' + s.timeMs + '" data-end="' + sEnd + '"' + progressStyle + '>' + escapeHtml(s.text) + '</span>';
      }
    } else {
      // 3. Standard line-level text
      lineTextHtml = escapeHtml(line.text || "♪");
      if (line.text === "•••") {
        if (isActive) {
          lineTextHtml = '<span class="intro-dots"><span class="dot dot-1">•</span><span class="dot dot-2">•</span><span class="dot dot-3">•</span></span>';
        } else {
          lineTextHtml = '•••';
        }
      }
    }

    var readingHtml = "";
    if (state.readingMode === 1 && romajiText) {
      if (state.isFullLyricsMode || isActive) {
        readingHtml = '<span class="romaji">' + buildRomajiInnerHTML(romajiText, Number(line.startTimeMs || 0), nextLine, syllables, isPast) + "</span>";
      }
    }

    var translation = line.translation ? '<span class="translation">' + escapeHtml(line.translation) + "</span>" : "";

    return readingHtml + '<span class="text">' + lineTextHtml + "</span>" + translation;
  }

  /**
   * Incremental patch for focused (default) mode.
   * Only updates CSS classes, inline styles, and romaji/dot content where needed.
   * Does NOT destroy/recreate DOM nodes.
   */
  function patchFocusedMode() {
    var active = clampedActiveIndex();
    var prevActive = prevActiveIndex;
    prevActiveIndex = active;
    state.activeIndex = active;

    var lines = lyricsEl.querySelectorAll(".line");
    if (!lines.length) return;

    var isSeek = prevActive === -1 || Math.abs(active - prevActive) > 3;
    var totalLines = lines.length;

    // Narrow the iteration range: only update lines whose visual state actually changed
    var loopStart, loopEnd;
    if (isSeek) {
      loopStart = 0;
      loopEnd = totalLines - 1;
    } else {
      // Only lines near prevActive and active need updates
      loopStart = Math.max(0, Math.min(prevActive, active) - 8);
      loopEnd = Math.min(totalLines - 1, Math.max(prevActive, active) + 8);
    }

    var hasReadingMode = state.readingMode > 0;

    for (var i = loopStart; i <= loopEnd; i += 1) {
      var distance = i - active;

      if (!isSeek) {
        var prevDistance = i - prevActive;
        // Skip elements that didn't change state (only past lines beyond -2 are stable)
        if (distance < -2 && prevDistance < -2) {
          continue;
        }
      }

      var el = lines[i];
      var kind = getKindClass(distance);
      var direction = getDirectionClass(distance);

      // Update classes
      el.classList.remove("active", "near", "far", "past", "future");
      el.classList.add(kind);
      if (direction) el.classList.add(direction);

      // Update inline styles (these will transition via CSS)
      el.setAttribute("style", getDistanceStyle(distance));

      // Update dots innerHTML only for the "•••" intro line
      var lineData = state.lyrics[i];
      if (lineData && lineData.text === "•••") {
        var textSpan = el.querySelector(".text");
        if (textSpan) {
          if (distance === 0) {
            textSpan.innerHTML = '<span class="intro-dots"><span class="dot dot-1">•</span><span class="dot dot-2">•</span><span class="dot dot-3">•</span></span>';
          } else {
            textSpan.textContent = '•••';
          }
        }
      }

      // Skip expensive reading/furigana processing if readingMode is off or line has no reading data
      if (!hasReadingMode || !lineData || !lineData.reading) {
        // Still remove stale romaji spans if reading mode was just turned off
        if (!hasReadingMode) {
          var existingRomaji = el.querySelector(".romaji");
          if (existingRomaji) existingRomaji.remove();
        }
        continue;
      }

      // --- Reading data processing (only reached when readingMode > 0 AND lineData.reading exists) ---
      var parsedReading = null;
      if (lineData.reading) {
        try {
          if (lineData.reading.startsWith("{")) {
            parsedReading = JSON.parse(lineData.reading);
          }
        } catch (e) {}
      }
      var romajiText = "";
      if (parsedReading) {
        romajiText = parsedReading.romaji || "";
      } else if (lineData && lineData.reading) {
        romajiText = lineData.reading;
      }

      var existingRomaji = el.querySelector(".romaji");
      if (state.readingMode === 1 && romajiText) {
        var nextLineData = state.lyrics[i + 1];
        var mainSyllables = parseSyllables(lineData.text || "", Number(lineData.startTimeMs || 0));
        var romajiHtml = buildRomajiInnerHTML(romajiText, Number(lineData.startTimeMs || 0), nextLineData, mainSyllables);
        if (distance === 0) {
          if (!existingRomaji) {
            var romajiSpan = document.createElement("span");
            romajiSpan.className = "romaji";
            romajiSpan.innerHTML = romajiHtml;
            el.insertBefore(romajiSpan, el.firstChild);
          } else {
            existingRomaji.innerHTML = romajiHtml;
          }
        } else {
          if (existingRomaji) {
            existingRomaji.remove();
          }
        }
      } else {
        if (existingRomaji) {
          existingRomaji.remove();
        }
      }

      // Update Furigana annotations dynamically in focused mode (show only on active line)
      var textSpan = el.querySelector(".text");
      if (textSpan && state.readingMode === 2 && lineData) {
        var furiganaHtml = parsedReading ? (parsedReading.furigana || "") : "";
        if (furiganaHtml) {
          var mainSyllables = parseSyllables(lineData.text || "", Number(lineData.startTimeMs || 0));
          var lineDuration = (state.lyrics[i + 1]) ? (state.lyrics[i + 1].startTimeMs - lineData.startTimeMs) : 4000;
          
          if (distance === 0) {
            // Active line: show Furigana (annotated Kanji with ruby tags)
            var activeHtml = buildFuriganaInnerHTML(furiganaHtml, mainSyllables, lineDuration, Number(lineData.startTimeMs || 0));
            if (textSpan.innerHTML !== activeHtml) {
              textSpan.innerHTML = activeHtml;
            }
          } else {
            // Inactive line: show clean original text (Kanji without ruby tags)
            var cleanHtml = "";
            if (mainSyllables.length > 0) {
              for (var sIdx = 0; sIdx < mainSyllables.length; sIdx++) {
                var s = mainSyllables[sIdx];
                var nextS = mainSyllables[sIdx + 1];
                var sEnd = s.durationMs ? (s.timeMs + s.durationMs) : (nextS ? nextS.timeMs : (lineData.startTimeMs + lineDuration));
                if (!s.durationMs && !nextS) {
                  sEnd = s.timeMs + Math.min(lineData.startTimeMs + lineDuration - s.timeMs, 1000);
                }
                if (sEnd <= s.timeMs) {
                  sEnd = s.timeMs + 200;
                }
                cleanHtml += '<span class="syllable" data-start="' + s.timeMs + '" data-end="' + sEnd + '">' + escapeHtml(s.text) + '</span>';
              }
            } else {
              cleanHtml = escapeHtml(lineData.text || "♪");
            }
            if (textSpan.innerHTML !== cleanHtml) {
              textSpan.innerHTML = cleanHtml;
            }
          }
        }
      }
    }

    // Smoothly move the container so the active line stays at the top-quarter of the viewport
    // with 2 past lines visible above it
    requestAnimationFrame(function () {
      if (userScrolling) return; // Don't fight user's manual scroll
      if (lines[active]) {
        var activeLine = lines[active];
        var containerHeight = getScrollContainer().clientHeight;
        var cs = getComputedStyle(lyricsEl);
        var padTop = parseFloat(cs.paddingTop) || 0;
        var anchor = containerHeight * 0.33;
        var translateY = anchor - padTop - activeLine.offsetTop;
        lyricsEl.style.transform = "translateY(" + translateY + "px)";
      }
    });
  }

  /**
   * Full render – builds or rebuilds all DOM nodes.
   * Called on initial load, lyrics change, romaji toggle, or mode switch.
   */
  function renderFull() {
    if (!state.lyrics.length) {
      lyricsEl.innerHTML = "";
      emptyEl.hidden = false;
      return;
    }

    var active = clampedActiveIndex();
    state.activeIndex = active;

    var html = "";
    for (var i = 0; i < state.lyrics.length; i += 1) {
      var line = state.lyrics[i] || {};
      var distance = i - active;
      var lengthClass = getLengthClass(line);
      var kind = getKindClass(distance);
      var direction = getDirectionClass(distance);

      var isActive = distance === 0;

      if (state.isFullLyricsMode) {
        html += '<article class="line ' + kind + " " + direction + " " + lengthClass + '"' +
          ' data-index="' + i + '">' +
          buildLineInnerHTML(line, isActive, state.lyrics[i + 1], distance < 0) +
          "</article>";
      } else {
        html += '<article class="line ' + kind + " " + direction + " " + lengthClass + '"' +
          ' data-index="' + i + '"' +
          ' style="' + getDistanceStyle(distance) + '">' +
          buildLineInnerHTML(line, isActive, state.lyrics[i + 1], distance < 0) +
          "</article>";
      }
    }

    lyricsEl.innerHTML = html;

    requestAnimationFrame(function () {
      var lines = lyricsEl.querySelectorAll(".line");
      if (lines[active]) {
        var activeLine = lines[active];
        var offset = activeLine.offsetTop;
        if (state.isFullLyricsMode) {
          lyricsEl.style.transform = "none";
          scrollToActiveIfNeeded(activeLine);
        } else {
          var containerHeight = getScrollContainer().clientHeight;
          var cs = getComputedStyle(lyricsEl);
          var padTop = parseFloat(cs.paddingTop) || 0;
          var anchor = containerHeight * 0.33;
          var translateY = anchor - padTop - offset;
          lyricsEl.style.transform = "translateY(" + translateY + "px)";
        }
      }
      fitFocusedFontSize();
      recacheActiveSyllables();
      updatePlaybackPosition();
    });

    report("render: active=" + active + " total=" + state.lyrics.length);
  }

  // Lightweight active-line update for full-lyrics-mode: swap classes without re-rendering DOM
  function updateActiveClassesOnly() {
    var active = clampedActiveIndex();
    var lines = lyricsEl.querySelectorAll(".line");
    if (!lines.length) return;

    for (var i = 0; i < lines.length; i += 1) {
      var el = lines[i];
      var distance = i - active;
      // Remove old state classes
      el.classList.remove("active", "near", "far", "past", "future");
      // Add new state classes
      if (distance === 0) {
        el.classList.add("active");
      } else if (Math.abs(distance) === 1) {
        el.classList.add("near");
      } else {
        el.classList.add("far");
      }
      if (distance < 0) {
        el.classList.add("past");
      } else if (distance > 0) {
        el.classList.add("future");
      }
    }

    // Auto-scroll only if active line is out of viewport
    if (lines[active]) {
      scrollToActiveIfNeeded(lines[active]);
    }
  }

  // ---------- Dynamic font sizing for focused right-aligned mode ----------
  // Binary-searches for the largest text font-size that makes
  // romaji + text + translation fit within the available content area.
  function fitFocusedFontSize() {
    // Font sizes are now handled by CSS for all layouts
    return;
  }

  function setInAppFontScale(scale) {
    try {
      var s = Number(scale) || 1.0;
      stageEl.style.setProperty('--in-app-font-scale', s);
      renderFull();
    } catch (error) {
      report("error:设置字号失败:" + error.message);
    }
  }

  window.LyricsPlus = {
    setTrack: setTrack,
    setLyrics: setLyrics,
    setPlaybackState: setPlaybackState,
    setReadingMode: setReadingMode,
    setRightAligned: setRightAligned,
    setInAppFontScale: setInAppFontScale
  };

  window.onerror = function (message, source, line) {
    report("error:" + message + "@" + line);
  };

  // ---------- Full-lyrics-mode: scroll-pause on user interaction ----------
  // When the user manually scrolls in full-lyrics-mode, we suppress auto-scroll
  // to the active line. After a period of no interaction, we resume auto-scroll.

  var SCROLL_RESUME_DELAY_MS = 3000; // 3 seconds after last touch → resume auto-scroll
  var userScrolling = false;
  var scrollResumeTimer = null;

  function onUserScrollInteraction() {
    if (!state.isFullLyricsMode) return;
    userScrolling = true;
    // Reset the resume timer
    if (scrollResumeTimer !== null) {
      clearTimeout(scrollResumeTimer);
    }
    scrollResumeTimer = setTimeout(function () {
      userScrolling = false;
      scrollResumeTimer = null;
      // Resume: immediately scroll to the current active line
      var lines = lyricsEl.querySelectorAll(".line");
      var active = clampedActiveIndex();
      if (lines[active]) {
        scrollToActiveIfNeeded(lines[active]);
      }
    }, SCROLL_RESUME_DELAY_MS);
  }

  // Listen for user-initiated scroll/touch on both possible scroll containers
  var viewportEl = document.querySelector(".lyrics-viewport");
  stageEl.addEventListener("touchstart", onUserScrollInteraction, { passive: true });
  stageEl.addEventListener("touchmove", onUserScrollInteraction, { passive: true });
  viewportEl.addEventListener("touchstart", onUserScrollInteraction, { passive: true });
  viewportEl.addEventListener("touchmove", onUserScrollInteraction, { passive: true });

  // Scroll active line — but only if the user isn't manually browsing
  function scrollToActiveIfNeeded(activeLine) {
    if (!state.isFullLyricsMode) return;
    if (userScrolling) return; // User is browsing, don't fight their scroll
    getScrollContainer().scrollTo({
      top: activeLine.offsetTop - getScrollContainer().offsetHeight * 0.05,
      behavior: "smooth"
    });
  }

  window.LyricsPlus = {
    setTrack: setTrack,
    setLyrics: setLyrics,
    setPlaybackState: setPlaybackState,
    setReadingMode: setReadingMode,
    setRightAligned: setRightAligned,
    setInAppFontScale: setInAppFontScale
  };

  window.onerror = function (message, source, line) {
    report("error:" + message + "@" + line);
  };

  // Click handler to toggle mode (skip if just finished a scroll gesture)
  stageEl.addEventListener("click", function (e) {
    if (wasScrollGesture) {
      wasScrollGesture = false;
      return;
    }
    if (state.lyrics.length > 0) {
      toggleFullLyricsMode();
    }
  });

  // --- Touch-to-browse support (focused mode, landscape lyrics pane) ---
  // Use stageEl (not lyricsEl) because .lyrics-viewport has pointer-events:none
  stageEl.addEventListener("touchstart", function (e) {
    if (state.isFullLyricsMode || state.lyrics.length === 0) return;
    lastTouchTime = performance.now();
    touchStartY = e.touches[0].clientY;
  }, { passive: true });

  stageEl.addEventListener("touchmove", function (e) {
    if (state.isFullLyricsMode || state.lyrics.length === 0) return;
    var now = performance.now();
    var dy = touchStartY - e.touches[0].clientY;
    if (!userScrolling) {
      // First significant movement → enter browse mode
      if (Math.abs(dy) < 10) return;
      userScrolling = true;
      wasScrollGesture = true;
      if (flingRafId !== null) cancelAnimationFrame(flingRafId);
      flingRafId = null;
      scrollVelocity = 0;
      lyricsEl.style.transition = "none";
      stageEl.classList.add("browsing");
      var match = lyricsEl.style.transform.match(/translateY\(([-\d.]+)px\)/);
      scrollOffset = match ? -parseFloat(match[1]) : 0;
      scrollBoundaries = getScrollBoundaries();
    }
    // Exponential moving average for velocity (px/ms)
    var dt = now - lastMoveTime;
    if (dt > 0 && dt < 100) {
      var instantV = dy / dt;
      scrollVelocity = scrollVelocity * 0.6 + instantV * 0.4;
    }
    lastMoveTime = now;
    scrollOffset += dy;
    scrollOffset = clampWithRubberBand(scrollOffset, scrollBoundaries.minScroll, scrollBoundaries.maxScroll);
    touchStartY = e.touches[0].clientY;
    lyricsEl.style.transform = "translateY(-" + scrollOffset + "px)";
  }, { passive: true });

  function startFling() {
    var VELOCITY_SCALE = 300;  // velocity (px/ms) → px
    var MIN_DURATION = 200;
    var MAX_DURATION = 800;

    var absV = Math.abs(scrollVelocity);
    var targetDelta = absV * VELOCITY_SCALE;
    var targetOffset = scrollOffset + Math.round(targetDelta) * (scrollVelocity > 0 ? 1 : -1);
    var duration = Math.round(MIN_DURATION + (absV / 10) * (MAX_DURATION - MIN_DURATION));
    duration = Math.max(MIN_DURATION, Math.min(MAX_DURATION, duration));

    // Clamp fling target to hard boundaries and adjust duration proportionally
    if (scrollBoundaries) {
      var clamped = Math.max(scrollBoundaries.minScroll, Math.min(scrollBoundaries.maxScroll, targetOffset));
      if (clamped !== targetOffset) {
        var originalDelta = Math.abs(targetOffset - scrollOffset);
        var clampedDelta = Math.abs(clamped - scrollOffset);
        var ratio = originalDelta > 0 ? clampedDelta / originalDelta : 1;
        targetOffset = clamped;
        duration = Math.round(Math.min(MAX_DURATION, MIN_DURATION + ratio * (duration - MIN_DURATION)));
      }
    }

    var startOffset = scrollOffset;
    var startTime = performance.now();

    function easeOutQuint(t) {
      return 1 - Math.pow(1 - t, 5);
    }

    function step() {
      var elapsed = performance.now() - startTime;
      var t = Math.min(elapsed / duration, 1);
      var eased = easeOutQuint(t);
      scrollOffset = startOffset + (targetOffset - startOffset) * eased;
      // Snap back if beyond boundaries during animation (shouldn't happen with clamped target, but safe)
      if (scrollBoundaries) {
        scrollOffset = clampWithRubberBand(scrollOffset, scrollBoundaries.minScroll, scrollBoundaries.maxScroll);
      }
      lyricsEl.style.transform = "translateY(-" + scrollOffset + "px)";
      if (t < 1) {
        flingRafId = requestAnimationFrame(step);
      } else {
        flingRafId = null;
        // Snap back to boundary if released beyond limits
        if (scrollBoundaries && (scrollOffset < scrollBoundaries.minScroll || scrollOffset > scrollBoundaries.maxScroll)) {
          scrollOffset = Math.max(scrollBoundaries.minScroll, Math.min(scrollBoundaries.maxScroll, scrollOffset));
          lyricsEl.style.transition = "";
          lyricsEl.style.transform = "translateY(-" + scrollOffset + "px)";
        }
        // Fling done → start idle timer for auto-resume
        scrollIdleTimer = setTimeout(function () {
          userScrolling = false;
          wasScrollGesture = false;
          stageEl.classList.remove("browsing");
          lyricsEl.style.transition = "";
          var lines = lyricsEl.querySelectorAll(".line");
          if (lines[state.activeIndex]) {
            var activeLine = lines[state.activeIndex];
            var containerHeight = getScrollContainer().clientHeight;
            var cs = getComputedStyle(lyricsEl);
            var padTop = parseFloat(cs.paddingTop) || 0;
            var anchor = containerHeight * 0.33;
            lyricsEl.style.transform = "translateY(" + (anchor - padTop - activeLine.offsetTop) + "px)";
          }
          updatePlaybackPosition();
        }, SCROLL_RESUME_DELAY);
      }
    }
    flingRafId = requestAnimationFrame(step);
  }

  stageEl.addEventListener("touchend", function () {
    if (!userScrolling) return;
    // Stop idle timer from any previous gesture
    if (scrollIdleTimer !== null) clearTimeout(scrollIdleTimer);
    if (flingRafId !== null) cancelAnimationFrame(flingRafId);

    if (Math.abs(scrollVelocity) > 0.3) {
      startFling();
    } else {
      // Snap back to boundary if released beyond limits
      if (scrollBoundaries && (scrollOffset < scrollBoundaries.minScroll || scrollOffset > scrollBoundaries.maxScroll)) {
        scrollOffset = Math.max(scrollBoundaries.minScroll, Math.min(scrollBoundaries.maxScroll, scrollOffset));
        lyricsEl.style.transition = "";
        lyricsEl.style.transform = "translateY(-" + scrollOffset + "px)";
      }
      // No significant velocity → start idle timer immediately
      scrollIdleTimer = setTimeout(function () {
        userScrolling = false;
        wasScrollGesture = false;
        stageEl.classList.remove("browsing");
        lyricsEl.style.transition = "";
        var lines = lyricsEl.querySelectorAll(".line");
        if (lines[state.activeIndex]) {
          var activeLine = lines[state.activeIndex];
          var containerHeight = getScrollContainer().clientHeight;
          var cs = getComputedStyle(lyricsEl);
          var padTop = parseFloat(cs.paddingTop) || 0;
          var anchor = containerHeight * 0.33;
          lyricsEl.style.transform = "translateY(" + (anchor - padTop - activeLine.offsetTop) + "px)";
        }
        updatePlaybackPosition();
      }, SCROLL_RESUME_DELAY);
    }
  }, { passive: true });

  // Re-calculate layout and scroll offsets on window resize (rotation / unfolding)
  window.addEventListener("resize", function () {
    try {
      if (state.lyrics.length > 0) {
        if (!state.isFullLyricsMode) {
          // In focused mode, do a full re-render so line visibility/sizing is correct
          prevActiveIndex = -1;
          lyricsViewportEl = null;
          lyricsEl.style.transition = "none";
          renderFull();
          requestAnimationFrame(function () {
            requestAnimationFrame(function () {
              lyricsEl.style.transition = "";
            });
          });
        } else {
          // In full-lyrics-mode, just re-scroll to active line
          var active = clampedActiveIndex();
          var lines = lyricsEl.querySelectorAll(".line");
          if (lines[active]) {
            scrollToActiveIfNeeded(lines[active]);
          }
        }
      }
      report("resize event handled");
    } catch (error) {
      report("error:窗口尺寸变化处理失败:" + error.message);
    }
  });

  function toggleFullLyricsMode() {
    state.isFullLyricsMode = !state.isFullLyricsMode;
    prevActiveIndex = -1;
    if (state.isFullLyricsMode) {
      userScrolling = false; // Reset scroll-pause state on enter
      stageEl.classList.add("full-lyrics-mode");
      // Reset scroll before rendering to avoid starting from previous position
      getScrollContainer().scrollTop = 0;
      renderFull();
      // Instantly jump to active line position (no smooth scroll from top)
      requestAnimationFrame(function () {
        var lines = lyricsEl.querySelectorAll(".line");
        if (lines[state.activeIndex]) {
          getScrollContainer().scrollTop = lines[state.activeIndex].offsetTop - window.innerHeight * 0.05;
        }
      });
    } else {
      userScrolling = false;
      if (scrollResumeTimer !== null) {
        clearTimeout(scrollResumeTimer);
        scrollResumeTimer = null;
      }
      stageEl.classList.remove("full-lyrics-mode");
      getScrollContainer().scrollTop = 0;
      // Disable transition to prevent upward bounce animation
      lyricsEl.style.transition = "none";
      renderFull();
      // Re-enable transition after position is painted
      requestAnimationFrame(function () {
        requestAnimationFrame(function () {
          lyricsEl.style.transition = "";
        });
      });
    }
    report("toggleFullLyricsMode: " + state.isFullLyricsMode);
    try {
      if (window.AndroidLyrics && window.AndroidLyrics.setFullLyricsMode) {
        window.AndroidLyrics.setFullLyricsMode(state.isFullLyricsMode);
      }
    } catch (ignore) { }
  }

  report("renderer ready");
})();
