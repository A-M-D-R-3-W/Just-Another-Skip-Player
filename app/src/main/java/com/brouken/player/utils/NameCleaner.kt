package com.brouken.player.utils

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

object NameCleaner {

    // 1. Season/Episode patterns (capture title group 1, S/E group 2, 3)
    // S01E01, S01 E01, s1e1
    private val PATTERN_S_E = Pattern.compile("(.+?)[\\s._-]+[Ss](\\d+)[\\s._-]*[Ee](\\d+)", Pattern.CASE_INSENSITIVE)
    // 1x01, 1x1
    private val PATTERN_X = Pattern.compile("(.+?)[\\s._-]+(\\d+)[xX](\\d+)")
    // Episode 1, Ep 1, E1
    private val PATTERN_EP = Pattern.compile("(.+?)[\\s._-]+(?:Episode|Ep|E)[\\s._-]*(\\d+)", Pattern.CASE_INSENSITIVE)
    
    // Anime Absolute: "Show - 01" or "Show - 01 - Title"
    // Capture group 1: Title, group 2: Episode
    private val PATTERN_ANIME_ABS = Pattern.compile("^(.+?)\\s*-\\s*(\\d{1,4})(?:\\s*-|\\s*\\[|\\s*\\(|$)", Pattern.CASE_INSENSITIVE)

    // Loose Absolute: "Show.1080.1999" (Show, Ep, Year) or "Show 100"
    // Finds a separated number that is followed by Year or specific tags, or end of string
    // Negative lookahead (?![pPi]) prevents matching "1080p" as episode 1080
    // Group 1: Title, Group 2: Episode
    private val PATTERN_LOOSE_ABSOLUTE = Pattern.compile("^(.+?)[\\s._-]+(\\d{1,4})(?![pPi\\d])(?:[\\s._-]+|$)", Pattern.CASE_INSENSITIVE)

    // Year pattern: "Movie Title (2023)" or "Movie.Title.2023."
    private val PATTERN_YEAR = Pattern.compile("(.+?)[\\s._\\-(]+(\\d{4})[)\\s._-]")

    // Junk Tags to strip (Case Insensitive)
    // CRITICAL: All patterns must be word-boundary or separator-based to prevent matching inside words!
    private val JUNK_REGEX = listOf(
        // Resolutions - must be followed by space or separator or end
        "\\b(2160|1080|720|480|576)[pP]\\b",
        "\\b(4|8)[kK]\\b", "\\b(UHD|HD|SD)\\b",
        
        // Sources - must be whole words with boundaries
        "\\b(BluRay|BDRip|BRRip|BD|DVD|DVDRip|DVDScr|R5)\\b",
        "\\b(WEB-DL|WEBRip|WEB|HDTV|PDTV|CAM|TS|TC|REMUX)\\b",
        
        // Codecs - must be whole words with boundaries
        "\\b((x|h)\\.?264|(x|h)\\.?265|HEVC|AVC|DivX|XviD|MPEG)\\b",
        
        // Audio - must be whole words with boundaries
        "\\b(TrueHD|DTS-HD|DTS|Atmos|DD(\\+|P)?\\s*5\\.1|DD|AAC|AC3|EAC3|FLAC|MP3)\\b",
        "\\b(5\\.1|7\\.1|2\\.0)\\b",
        
        // HDR / Video specs - must be whole words with boundaries
        "\\b(HDR(10)?(\\+)?|Dolby\\s*Vision|DV|10bit|12bit|Hi10P|SDR)\\b",
        "\\b(AI\\s*Upscale|Upscaled)\\b",
        
        // Release Types / Misc - must be whole words with boundaries
        "\\b(REPACK|PROPER|REAL|INTERNAL|FESTIVAL|STV|LIMITED|UNRATED|DC|EXTENDED|REMASTERED|COMPLETE|RESTORED|UNCUT|DIRECTOR'?S\\s*CUT)\\b",
        
        // Languages - must be whole words with boundaries
        "\\b(MULTI|DUAL|LATINO|FRENCH|GERMAN|SPANISH|ITA|RUS|JAP|ENG|SUB|DUB)\\b",
        
        // Groups / Hash - must be in brackets at boundaries
        "[\\s._-]\\[[^\\]]+\\]",  // [HorribleSubs] with separator before
        "[\\s._-]\\([^\\)]+\\)",   // (Source) with separator before
        "-[\\w\\d]+$"        // -Group at end (alphanumeric only)
    ).joinToString("|") // Combine with OR

    private val JUNK_PATTERN = Pattern.compile(JUNK_REGEX, Pattern.CASE_INSENSITIVE)

    data class CleanResult(
        val showName: String,
        val season: Int,
        val episode: Int,
        val year: Int? = null,
        val isAnime: Boolean = false
    )

    fun clean(filename: String): CleanResult {
        var name = filename
        var season = 1
        var episode = 1
        var year: Int? = null
        var isAnime = false

        DebugLogger.log("NameCleaner", "========================================")
        DebugLogger.log("NameCleaner", "CLEANING FILENAME: '$filename'")
        DebugLogger.log("NameCleaner", "========================================")

        // 1. Try S/E Patterns first (Strongest signal for TV)
        var matcher = PATTERN_S_E.matcher(name)
        if (matcher.find()) {
            name = matcher.group(1).trim()
            season = matcher.group(2).toInt()
            episode = matcher.group(3).toInt()
            DebugLogger.log("NameCleaner", " Matched S/E pattern: '$name' S$season E$episode")
        } else {
            matcher = PATTERN_X.matcher(name)
            if (matcher.find()) {
                name = matcher.group(1).trim()
                season = matcher.group(2).toInt()
                episode = matcher.group(3).toInt()
                DebugLogger.log("NameCleaner", " Matched X pattern: '$name' S$season E$episode")
            } else {
                // Try Anime format (Show - 01)
                matcher = PATTERN_ANIME_ABS.matcher(name)
                if (matcher.find()) {
                    name = matcher.group(1).trim()
                    episode = matcher.group(2).toInt()
                    season = 1 // Anime typically uses absolute episode numbers
                    isAnime = true
                    DebugLogger.log("NameCleaner", " Matched Anime pattern: '$name' E$episode")
                } else {
                    // Try simple Episode pattern
                    matcher = PATTERN_EP.matcher(name)
                    if (matcher.find()) {
                        name = matcher.group(1).trim()
                        episode = matcher.group(2).toInt()
                        DebugLogger.log("NameCleaner", " Matched Ep pattern: '$name' E$episode")
                    } else {
                        // Fallback: Loose Absolute Number (Show 1080)
                        // Useful for "One.Piece.1080.WEBRip"
                        matcher = PATTERN_LOOSE_ABSOLUTE.matcher(name)
                        if (matcher.find()) {
                            // Only accept if it looks safe
                            val potentialName = matcher.group(1).trim()
                            val potentialEp = matcher.group(2).toInt()
                            
                            // Safety Check: Don't treat "Movie 2024" as Ep 2024 if it looks like a Year
                            // Heuristic: If we find a Year elsewhere, OR if number is NOT 19xx/20xx, accept it.
                            val yearMatcher = PATTERN_YEAR.matcher(filename)
                            val hasYear = yearMatcher.find()
                            val isLikelyYear = (potentialEp in 1900..2100)
                            
                            if (hasYear && yearMatcher.group(2).toInt() == potentialEp) {
                                // The number matched is actually the year (duplicated or primary)
                                // e.g. "Movie.2000.mkv" -> 2000 is the year, not Ep.
                                DebugLogger.log("NameCleaner", " Loose match $potentialEp skipped (appears to be Year)")
                            } else if (!isLikelyYear || hasYear) {
                                // Accept as Episode if it's NOT a year-like number, OR if valid year covers it
                                // "One.Piece.1080" (1080 is fine as Ep if 1999 is Year)
                                
                                name = potentialName
                                episode = potentialEp
                                season = 1
                                isAnime = true
                                DebugLogger.log("NameCleaner", " Matched Loose Absolute pattern: '$name' E$episode")
                            }
                        }
                    }
                }
            }
        }

        // 2. Extract Year if present (and not already part of title)
        DebugLogger.log("NameCleaner", "Step 2: Checking for year pattern...")
        matcher = PATTERN_YEAR.matcher(name)
        if (matcher.find()) {
            val potentialTitle = matcher.group(1).trim()
            // Only accept if title isn't empty (e.g. "2024.mkv" -> empty)
            if (potentialTitle.length > 1) {
                name = potentialTitle
                year = matcher.group(2).toInt()
                DebugLogger.log("NameCleaner", " Extracted Year: '$name' ($year)")
            }
        }

        // 3. Bruteforce sanitize "Scene Tags"
        DebugLogger.log("NameCleaner", "Step 3: Applying junk regex patterns...")
        DebugLogger.log("NameCleaner", "  Before junk removal: '$name'")
        val beforeJunk = name
        // remove anything matching junk regex
        name = JUNK_PATTERN.matcher(name).replaceAll(" ")
        DebugLogger.log("NameCleaner", "  After junk removal: '$name' (changed: ${beforeJunk != name})")
        
        // 4. Final Cleanup
        DebugLogger.log("NameCleaner", "Step 4: Final cleanup steps...")
        
        // Remove file extensions
        val beforeExt = name
        name = name.replace(Regex("\\.(mkv|mp4|avi|webm|mov)$", RegexOption.IGNORE_CASE), "")
        DebugLogger.log("NameCleaner", "  4a. Removed extension: '$beforeExt' � '$name'")
        
        // Replace dots/underscores with spaces
        val beforeDots = name
        name = name.replace(".", " ").replace("_", " ")
        DebugLogger.log("NameCleaner", "  4b. Dots/underscores � spaces: '$beforeDots' � '$name'")
        
        // Collapse multiple spaces
        val beforeSpaces = name
        name = name.replace(Regex("\\s+"), " ").trim()
        DebugLogger.log("NameCleaner", "  4c. Collapsed spaces: '$beforeSpaces' � '$name'")
        
        // Remove trailing hyphens
        val beforeHyphens = name
        name = name.replace(Regex("[-]+$"), "").trim()
        DebugLogger.log("NameCleaner", "  4d. Removed trailing hyphens: '$beforeHyphens' � '$name'")
        
        DebugLogger.log("NameCleaner", "========================================")
        DebugLogger.log("NameCleaner", "FINAL RESULT: '$name' S$season E$episode")
        DebugLogger.log("NameCleaner", "  Year: $year, IsAnime: $isAnime")
        DebugLogger.log("NameCleaner", "========================================")
        
        return CleanResult(name, season, episode, year, isAnime)
    }
    
    /**
     * Extract and format display title from filename using tokenizer approach
     * Returns formatted string like:
     * - "Movie Title (2023)" for movies
     * - "Show Name S01E01" for TV shows
     */
    fun extractDisplayTitle(filename: String): String {
        // First check if it's a TV show with season/episode
        val tvResult = extractTVInfo(filename)
        if (tvResult != null) {
            val (title, season, episode) = tvResult
            val cleanTitle = extractTitleFromTokens(title)
            if (cleanTitle.isNotEmpty()) {
                return String.format("%s S%02dE%02d", cleanTitle, season, episode)
            }
        }
        
        // For movies, extract title using tokenizer
        val movieTitle = extractTitleFromTokens(filename)
        if (movieTitle.isNotEmpty()) {
            // Try to extract year
            val year = extractYear(filename)
            return if (year != null) {
                String.format("%s (%d)", movieTitle, year)
            } else {
                movieTitle
            }
        }
        
        // Fallback to original filename
        return filename
    }
    
    /**
     * Extract TV show info (title, season, episode) from filename
     */
    private fun extractTVInfo(filename: String): Triple<String, Int, Int>? {
        // Try S01E01 pattern
        var matcher = PATTERN_S_E.matcher(filename)
        if (matcher.find()) {
            return Triple(matcher.group(1), matcher.group(2).toInt(), matcher.group(3).toInt())
        }
        
        // Try 1x01 pattern
        matcher = PATTERN_X.matcher(filename)
        if (matcher.find()) {
            return Triple(matcher.group(1), matcher.group(2).toInt(), matcher.group(3).toInt())
        }
        
        return null
    }
    
    /**
     * Extract year from filename
     */
    private fun extractYear(filename: String): Int? {
        val matcher = PATTERN_YEAR.matcher(filename)
        if (matcher.find()) {
            val year = matcher.group(2).toInt()
            if (year in 1900..2099) {
                return year
            }
        }
        return null
    }
    
    /**
     * Extract title from filename using tokenizer approach
     * Stops at boundary tokens (year, resolution, codec, source, etc.)
     */
    private fun extractTitleFromTokens(filename: String): String {
        // Try to strip any URI/path prefix and decode %2F etc so we only tokenize the real name.
        // Examples we want to handle:
        // - "/storage/.../Downloads/Sopranos.S01E01.1080p.mkv"
        // - "content://.../file%2FDownload%2FSopranos.S01E01.1080p.mkv"
        // - "file:///.../Sopranos.S01E01.1080p.mkv"
        var base = filename
            .substringBefore('?') // drop query params if present
            .substringAfterLast('/') // last path segment (may still be url-encoded)

        base = try {
            URLDecoder.decode(base, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            base
        }

        // If decode produced embedded slashes (e.g. "file/Download/Sopranos..."), keep only the final segment
        base = base.substringAfterLast('/')

        // Remove extension (if any)
        base = base.substringBeforeLast(".")
        
        // Remove trailing bracket groups [YTS], {rarbg}, (www.site.com)
        base = base.replace(Regex("\\s*[\\[\\{].*[\\]\\}]\\s*$"), "")
        
        // Normalize separators
        var s = base
        s = s.replace("_", " ")
        s = s.replace(".", " ")
        s = s.replace("-", " - ") // Keep group delimiter visible
        s = s.replace(Regex("\\s+"), " ").trim()
        
        // Split into tokens
        val tokens = s.split(" ").filter { it.isNotEmpty() }
        
        // Remove group suffix if present (e.g., "Title ... -GROUP")
        val processedTokens = if (tokens.lastOrNull()?.startsWith("-") == true) {
            val dashIndex = tokens.indexOfLast { it.startsWith("-") }
            if (dashIndex > 0) tokens.subList(0, dashIndex) else tokens
        } else {
            tokens
        }
        
        // Boundary words that indicate end of title
        val boundaryWords = setOf(
            // Source/type
            "webrip", "web", "webdl", "web-dl", "web-dlrip", "hdtv", "dvdrip", "bdrip", 
            "bluray", "blu-ray", "remux", "hdrip", "cam", "telesync", "ts", "tc",
            // Resolution
            "480p", "576p", "720p", "1080p", "1440p", "2160p", "4k", "8k", "uhd", "hd", "sd",
            // Codecs
            "x264", "x265", "h264", "h265", "hevc", "avc", "divx", "xvid", "vp9", "av1", "h.264", "h.265",
            // Audio
            "aac", "ac3", "eac3", "ddp", "dts", "truehd", "atmos", "mp3", "flac", "7.1", "5.1", "2.0",
            "ddp5.1", "dts-hd", "truehd7.1",
            // Misc tags
            "hdr", "hdr10", "dv", "dolbyvision", "10bit", "8bit", "remastered", "extended", 
            "unrated", "directors", "director's", "cut", "proper", "repack", "limited", 
            "internal", "subbed", "dubbed", "multisub", "nf", "amzn", "hmax", "dsnp"
        )
        
        // Helper functions
        fun isYear(token: String): Boolean {
            return token.matches(Regex("\\d{4}")) && token.toIntOrNull()?.let { it in 1900..2099 } == true
        }
        
        fun isBoundaryToken(token: String): Boolean {
            val lower = token.lowercase()
            if (lower in boundaryWords) return true
            if (isYear(lower)) return true
            if (lower.matches(Regex("\\d+p"))) return true // Resolution pattern
            if (lower.matches(Regex("ddp\\d+\\.\\d+"))) return true // Audio pattern like ddp5.1
            if (lower.matches(Regex("(x|h)\\.?\\d+"))) return true // Codec pattern
            return false
        }
        
        // Walk left-to-right, take tokens until boundary
        val titleTokens = mutableListOf<String>()
        for (token in processedTokens) {
            if (token.isEmpty()) continue
            
            // Check if token is a boundary
            if (isBoundaryToken(token)) {
                break
            }
            
            // Check for year in parentheses: "(2023)"
            if (token.startsWith("(") && token.endsWith(")")) {
                val inner = token.removePrefix("(").removeSuffix(")")
                if (isYear(inner)) {
                    break
                }
            }
            
            titleTokens.add(token)
        }
        
        if (titleTokens.isEmpty()) {
            return ""
        }
        
        // Join and clean up
        var title = titleTokens.joinToString(" ")
        title = title.replace(Regex("^[\"'\\s]+|[\"'\\s]+$"), "") // Remove surrounding quotes/parens
        title = title.trim()
        
        return title
    }
}