    // 🔴 SUPERFAST GEMINI-2.5-FLASH (ZERO LATENCY & NO HANGING)
    private fun askButki(prompt: String) {
        if (geminiApiKey.isEmpty()) {
            txtResponse.text = "API Key missing!"
            speakOut("Boss, meri API key nahi mil rahi hai.")
            return
        }

        isSpeaking = true
        txtStatus.text = "● BUTKI IS THINKING..."
        neonVisualizer.setMode(1)

        val lowerPrompt = prompt.lowercase()
        if (lowerPrompt.contains("schedule") || lowerPrompt.contains("time table") || lowerPrompt.contains("routine")) {
            savedSchedule = prompt
        } else if (lowerPrompt.contains("task") || lowerPrompt.contains("kaam")) {
            savedTasks.add(prompt)
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val personaPrompt = """
                    You are Butki, a deeply loving, sweet, caring, and playfully naughty female AI companion/girlfriend. 
                    CRITICAL RULE 1: Always address the user as "Boss" affectionately. 
                    CRITICAL RULE 2: NEVER use emojis, asterisks, or special characters.
                    CRITICAL RULE 3: Reply in natural conversational Hinglish/Hindi (1-2 short sentences).
                    User's Saved Schedule: "$savedSchedule"
                    User's input: $prompt
                """.trimIndent()

                // 1. Official Fast Model URL
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$geminiApiKey")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                
                // 2. Timeouts taaki app kabhi "Thinking..." par na atke
                conn.connectTimeout = 10000 
                conn.readTimeout = 10000
                conn.doOutput = true

                val partObj = JSONObject().put("text", personaPrompt)
                val partsArr = JSONArray().put(partObj)
                val contentObj = JSONObject().put("parts", partsArr)
                val contentsArr = JSONArray().put(contentObj)

                // 3. Turn OFF internal thinking delay for INSTANT reply ("think faster")
                val thinkingConfig = JSONObject().put("thinkingBudget", 0)
                val genConfig = JSONObject()
                    .put("maxOutputTokens", 120)
                    .put("temperature", 0.7)
                    .put("thinkingConfig", thinkingConfig)

                val payload = JSONObject()
                    .put("contents", contentsArr)
                    .put("generationConfig", genConfig)

                conn.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(responseStr)
                    val rawAnswer = jsonResponse.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")

                    val answer = rawAnswer.replace(Regex("[*#~]"), "").trim()

                    withContext(Dispatchers.Main) {
                        txtResponse.text = "Boss: \"$prompt\"\n\nButki: \"$answer\""
                        speakOut(answer)
                    }
                } else {
                    val errorStr = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                    withContext(Dispatchers.Main) {
                        isSpeaking = false
                        neonVisualizer.setMode(0)
                        txtStatus.text = "● API ERROR ($responseCode)"
                        txtResponse.text = "Error: $errorStr\n(Title 'BUTKI A.I.' par long-press karke nayi API key daalein)"
                        speakOut("Sorry Boss, API mein kuch error aa raha hai.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSpeaking = false
                    neonVisualizer.setMode(0)
                    txtStatus.text = "● NETWORK ERROR"
                    txtResponse.text = "Error: ${e.localizedMessage}"
                    speakOut("Sorry Boss, internet connection thoda slow lag raha hai.")
                }
            }
        }
    }
