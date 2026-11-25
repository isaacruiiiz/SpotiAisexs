package com.spotiaisexs.app.data.mood

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricSignalsTest {
    @Test
    fun recognisesTheLanguageOfTheLyrics() {
        val spanish = "Y yo que te quiero tanto, que no puedo vivir sin ti, cuando la noche llega y tú no estás aquí, " +
            "mi corazón se rompe, porque eres todo lo que tengo en esta vida"
        val english = "And I know that you love me, but I just can't let you go, when the night is over " +
            "I will be there for you, baby, all the time"
        assertThat(LyricSignals.analyse(spanish).language).isEqualTo(1)
        assertThat(LyricSignals.analyse(english).language).isEqualTo(-1)
        assertThat(LyricSignals.analyse("la la la oh oh").language).isEqualTo(0)
    }

    @Test
    fun noticesPartyingAndPushingOn() {
        assertThat(LyricSignals.analyse("Vamos a la fiesta, baila toda la noche, perreo hasta abajo, que siga la fiesta").party).isTrue()
        assertThat(LyricSignals.analyse("Never give up, stronger every day, I'm a fighter, I'm unstoppable, I rise").motivation).isTrue()
        assertThat(LyricSignals.analyse("Te echo de menos cada día").party).isFalse()
    }
}
