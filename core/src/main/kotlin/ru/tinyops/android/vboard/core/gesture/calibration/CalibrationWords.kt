package ru.tinyops.android.vboard.core.gesture.calibration

/**
 * The words a calibration asks to swipe, in a fixed order so that recordings compare: short frequent
 * words, everyday words, long words, paths that turn back or run along one row, and `ё`, `ъ`, `ь`.
 */
object CalibrationWords {
    val RUSSIAN: List<String> =
        """
            привет как дела сегодня на хорошо поход спасибо действительно не ещё завтра что полоса
            пожалуйста это информация всё мы работа топот вопрос так обязательно человек объявление
            вот время ворона был деньги программа потом кукуруза может подъезд сейчас возможность мама
            только большой конечно представляешь папа нормально день понятно пример интересно давай
            тетрадь вечером ерунда неожиданно отлично съесть все приложение шоколад жизнь клавиатура
            чуть щука фильм хочешь здравствуйте проверка слово
        """.split(' ', '\n').map(String::trim).filter(String::isNotEmpty)
}
