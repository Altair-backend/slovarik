package ru.slovarik.app.data

import ru.slovarik.app.data.db.CardEntity
import ru.slovarik.app.data.db.FolderEntity
import ru.slovarik.app.data.db.SetEntity

/** Демо-набор из 20 английских слов, который создаётся при первом запуске. */
object DemoData {

    private val words = listOf(
        Triple("achieve", "достигать", "She worked hard to achieve her goal."),
        Triple("borrow", "брать взаймы", "Can I borrow your pen for a minute?"),
        Triple("challenge", "вызов, трудная задача", "Learning a language is a real challenge."),
        Triple("decide", "решать, принимать решение", "We decided to stay at home."),
        Triple("environment", "окружающая среда", "We must protect the environment."),
        Triple("familiar", "знакомый", "His face looks familiar to me."),
        Triple("goal", "цель", "My goal is to learn ten words a day."),
        Triple("habit", "привычка", "Reading before bed is a good habit."),
        Triple("improve", "улучшать", "I want to improve my English."),
        Triple("journey", "путешествие, поездка", "The journey took three hours."),
        Triple("knowledge", "знание", "Knowledge is power."),
        Triple("lonely", "одинокий", "He felt lonely in the new city."),
        Triple("mention", "упоминать", "She didn't mention the meeting."),
        Triple("neighbour", "сосед", "Our neighbour has two dogs."),
        Triple("opportunity", "возможность", "It was a great opportunity to travel."),
        Triple("patient", "терпеливый", "Be patient, it takes time."),
        Triple("quiet", "тихий", "The library is a quiet place."),
        Triple("receive", "получать", "Did you receive my message?"),
        Triple("suggest", "предлагать", "I suggest we take a break."),
        Triple("weather", "погода", "The weather is lovely today."),
    )

    suspend fun insert(repository: Repository) {
        val folderId = repository.saveFolder(FolderEntity(name = "Английский", color = 0, icon = 2))
        val setId = repository.saveSet(
            SetEntity(
                folderId = folderId,
                name = "Английский: 20 полезных слов",
                description = "Демо-набор. Его можно изменить или удалить.",
                termLang = "en",
                defLang = "ru",
            ),
        )
        words.forEachIndexed { index, (term, definition, example) ->
            repository.saveCard(
                CardEntity(setId = setId, term = term, definition = definition, example = example, position = index + 1),
            )
        }
    }
}
