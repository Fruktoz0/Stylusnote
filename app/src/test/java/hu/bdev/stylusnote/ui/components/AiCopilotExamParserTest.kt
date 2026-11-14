package hu.bdev.stylusnote.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AiCopilotExamParserTest {

    @Test
    fun parseExamPackJsonExtractsFlashcardsAndQuizFromFencedGeminiOutput() {
        val raw = """
            ```json
            {
              "title": "Analízis I. – Vizsgacsomag",
              "flashcards": [
                {
                  "front": "Mi a Cauchy-féle kritérium?",
                  "back": "Egy sorozat pontosan akkor konvergens R-ben, ha Cauchy-sorozat.",
                  "topicTag": "Sorozatok"
                },
                {
                  "front": "Mi f'(x), ha f(x) = e^x?",
                  "back": "e^x",
                  "topicTag": "Differenciálszámítás"
                }
              ],
              "questions": [
                {
                  "question": "Mennyi lim_{x->0} sin(x)/x értéke?",
                  "options": ["0", "1", "végtelen", "nem létezik"],
                  "correctIndex": 1,
                  "explanation": "Nevezetes trigonometrikus határérték."
                }
              ]
            }
            ```
        """.trimIndent()

        val pack = AiCopilotParsers.parseExamPackJson(raw)
        assertEquals("Analízis I. – Vizsgacsomag", pack.title)
        assertEquals(2, pack.flashcards.size)
        assertEquals("Sorozatok", pack.flashcards[0].topicTag)
        assertEquals(1, pack.questions.size)
        assertEquals(1, pack.questions[0].correctIndex)
        assertEquals(4, pack.questions[0].options.size)
    }

    @Test
    fun parseMagicCircleJsonExtractsSummaryAndExamples() {
        val raw = """
            {
              "title": "Sajátérték és sajátvektor",
              "summaryMarkdown": "Az A*v = lambda*v egyenlet megoldásai...",
              "examples": [
                "Forgatás mátrixa R^2-ben",
                "Főtengeyek keresése"
              ]
            }
        """.trimIndent()

        val explanation = AiCopilotParsers.parseMagicCircleJson(raw)
        assertEquals("Sajátérték és sajátvektor", explanation.title)
        assertEquals(2, explanation.examples.size)
    }
}

