const express = require("express");
const cors = require("cors");
const youtubedl = require("youtube-dl-exec");

const app = express();
app.use(cors());
app.use(express.json());

// 1. Search YouTube Tracks
app.get("/api/search", async (req, res) => {
  const query = req.query.q;
  if (!query) {
    return res.status(400).json({ error: "Query parameter 'q' is required" });
  }

  try {
    const searchResults = await youtubedl(`ytsearch10:${query}`, {
      dumpSingleJson: true,
      noWarnings: true,
      noCallHome: true,
      preferFreeFormats: true,
      youtubeSkipDashManifest: true
    });

    const entries = searchResults.entries || [searchResults];
    const results = entries.map(item => ({
      id: item.id,
      title: item.title || item.fulltitle || "Unknown Track",
      artist: item.uploader || item.channel || "YouTube",
      duration: item.duration || 0,
      thumbnail: item.thumbnail || (item.thumbnails && item.thumbnails[0] ? item.thumbnails[0].url : "")
    }));

    res.json({ query, results });
  } catch (error) {
    console.error("Search Error:", error.message);
    res.status(500).json({ error: "Failed to search YouTube", details: error.message });
  }
});

// 2. Stream YouTube Audio URL
app.get("/api/stream", async (req, res) => {
  const videoId = req.query.id;
  if (!videoId) {
    return res.status(400).json({ error: "Query parameter 'id' is required" });
  }

  try {
    const videoUrl = `https://www.youtube.com/watch?v=${videoId}`;
    const info = await youtubedl(videoUrl, {
      dumpSingleJson: true,
      noWarnings: true,
      format: "bestaudio/best"
    });

    res.json({
      id: info.id || videoId,
      streamUrl: info.url || (info.formats && info.formats[0] ? info.formats[0].url : ""),
      title: info.title || info.fulltitle || "Online Track",
      artist: info.uploader || info.channel || "YouTube",
      duration: info.duration || 0,
      thumbnail: info.thumbnail || ""
    });
  } catch (error) {
    console.error("Stream Error:", error.message);
    res.status(500).json({ error: "Failed to extract stream URL", details: error.message });
  }
});

const PORT = process.env.PORT || 3000;
const HOST = "0.0.0.0"; // Listen on all network interfaces for Android device access

app.listen(PORT, HOST, () => {
  console.log(`🚀 Antigravity YouTube Backend running on http://${HOST}:${PORT}`);
  console.log(`- Android Emulator: http://10.0.2.2:${PORT}`);
  console.log(`- Local PC / Real Device: http://<YOUR_PC_LOCAL_IP>:${PORT}`);
});
