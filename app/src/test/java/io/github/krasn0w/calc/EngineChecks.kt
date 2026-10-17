package io.github.krasn0w.calc

/** Plain checks, callable from JUnit in CI and from a bare main() locally. */
object EngineChecks {

    private fun press(keys: String, e: Engine = Engine()): Engine {
        // Key script: digits, '.', + - * / ^, ( ), r = sqrt, % , n = toggle sign, < = backspace, = , c = clear
        // S/R/P/M/Z = memory store/recall/plus/minus/clear
        for (k in keys) when (k) {
            in '0'..'9' -> e.digit(k)
            '.' -> e.decimal()
            '+', '-', '*', '/', '^' -> e.op(k)
            '(' -> e.lparen()
            ')' -> e.rparen()
            'r' -> e.sqrt()
            '%' -> e.percent()
            'n' -> e.toggleSign()
            '<' -> e.backspace()
            '=' -> e.equals()
            'c' -> e.clear()
            'S' -> e.memStore()
            'R' -> e.memRecall()
            'P' -> e.memAdd()
            'M' -> e.memSub()
            'Z' -> e.memClear()
            ' ' -> Unit
            else -> error("bad key $k")
        }
        return e
    }

    private fun clean(s: String) = s.replace(' ', ' ').replace(' ', '_').replace('−', '-')

    private fun checkResult(keys: String, expected: String) {
        val e = press(keys)
        val got = clean(e.expressionText())
        check(got == expected) { "keys '$keys': expected '$expected', got '$got' (err=${e.errorMessage})" }
    }

    private fun checkPreview(keys: String, expected: String?) {
        val got = press(keys).preview()?.let(::clean)
        check(got == expected) { "preview '$keys': expected '$expected', got '$got'" }
    }

    private fun checkError(keys: String, msg: String) {
        val e = press(keys)
        check(e.errorMessage == msg) { "keys '$keys': expected error '$msg', got '${e.errorMessage}'" }
    }

    fun runAll(): Int {
        var n = 0
        fun r(k: String, x: String) { checkResult(k, x); n++ }

        // basics and precedence
        r("2+3*4=", "14")
        r("10/4=", "2,5")
        r("0.1+0.2=", "0,3")
        r("1/3=", "0,333333333333")
        r("2/3*3=", "2")
        r("1234567*1000=", "1_234_567_000")
        // brackets, auto-close
        r("(2+3)*4=", "20")
        r("2(3+4=", "14")          // implicit multiplication + auto-close
        r("((1+2)*(3+4))=", "21")
        // power, right associative
        r("2^10=", "1_024")
        r("2^3^2=", "512")
        r("2^-2=", "0,25")         // minus after ^ starts a negative exponent
        r("9^.5=", "3")
        r("2^0.5=", "1,41421356237")
        // sqrt
        r("r16=", "4")
        r("r(9+16=", "5")
        r("2r9=", "6")
        r("r2=", "1,41421356237")
        // percent semantics
        r("200+10%=", "220")
        r("200-10%=", "180")
        r("200*10%=", "20")
        r("50%=", "0,5")
        r("200/50%=", "400")
        // sign toggle
        r("5n=", "-5")
        r("5n", "-5")
        r("3*5n", "3 × (-5)")
        r("3*5n=", "-15")
        r("3-n5=", "8")            // n after operator starts negative number
        r("5nn", "5")
        r("-5+2=", "-3")           // leading minus
        r("2*3=n", "-6")           // toggle result
        // editing
        r("123<", "12")
        r("12+<", "12")
        r("12+3=<", "0")           // backspace after result clears
        r("2+3=4", "4")            // digit after result starts fresh
        r("2+3=*2=", "10")         // operator after result continues
        r("5++*", "5 × ")
        r("1.2.3", "1,23")
        r(".5*2=", "1")
        r("00012", "12")
        r("c", "0")
        // memory
        r("12*3S c R", "36")
        r("10P 5P c R", "15")
        r("10S 3M c R", "7")
        r("7S c 2+R=", "9")
        r("7S c 2+R*2=", "16")
        r("4S c 1+2=R", "4")       // MR after = replaces the result
        r("4S c 1+2=RZ c R", "0")  // Z clears memory, then R does nothing
        // large / small numbers
        r("10^20=", "1×10²⁰")
        r("2^-30=", "9,31322574615×10⁻¹⁰")
        r("123456789012345678", "123_456_789_012_345")   // digit cap

        // preview
        checkPreview("2+3", "5"); n++
        checkPreview("2+3*", "5"); n++
        checkPreview("(2+3", "5"); n++
        checkPreview("12", null); n++
        checkPreview("2+3=", null); n++
        checkPreview("r", null); n++

        // errors
        checkError("5/0=", "Деление на ноль"); n++
        checkError("rn4=", "Корень из отрицательного"); n++
        checkError("0^-1=", "Деление на ноль"); n++
        checkError("n2^.5=", "Ошибка"); n++
        // error leaves expression editable
        run { val e = press("5/0="); check(clean(e.expressionText()) == "5 ÷ 0"); e.backspace(); e.digit('2'); e.equals()
            check(clean(e.expressionText()) == "2,5") { "recover: ${e.expressionText()}" }; n++ }

        // last expression shown after '='
        run { val e = press("2+3*(4"); e.equals(); check(clean(e.lastExpression) == "2 + 3 × (4)") { e.lastExpression }; n++ }
        return n
    }
}

fun main() {
    val n = EngineChecks.runAll()
    println("OK: $n checks passed")
}
