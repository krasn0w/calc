package io.github.krasn0w.calc

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Expression-based calculator engine. Pure Kotlin, no Android dependencies,
 * so it can be unit-tested on the JVM.
 *
 * Internally numbers use '.' as the decimal separator; the display uses ','.
 */
class Engine {

    sealed class Tok {
        /** A number. [text] is what the user typed; [exact] is set for results and memory recalls. */
        data class Num(val text: String = "", val negative: Boolean = false, val exact: BigDecimal? = null) : Tok() {
            val isPending: Boolean get() = exact == null && text.isEmpty()
            val isNeg: Boolean get() = if (exact != null) exact.signum() < 0 else negative
            fun value(): BigDecimal {
                exact?.let { return it }
                val t = text.trimEnd('.')
                if (t.isEmpty()) throw CalcError("Ошибка")
                val v = BigDecimal(t)
                return if (negative) v.negate() else v
            }
        }
        data class Op(val c: Char) : Tok()
        object LParen : Tok()
        object RParen : Tok()
        object Sqrt : Tok()
        object Percent : Tok()
    }

    class CalcError(message: String) : Exception(message)

    val tokens = mutableListOf<Tok>()
    var justEvaluated = false
        private set
    var lastExpression: String = ""
        private set
    var errorMessage: String? = null
        private set
    var memory: BigDecimal? = null

    private val last: Tok? get() = tokens.lastOrNull()

    private fun lastIsValueEnd(): Boolean {
        val t = last
        return (t is Tok.Num && !t.isPending) || t is Tok.RParen || t is Tok.Percent
    }

    private fun beginEdit() {
        errorMessage = null
        lastExpression = ""
    }

    // ---------- input ----------

    fun digit(d: Char) {
        require(d in '0'..'9')
        beginEdit()
        if (justEvaluated) { tokens.clear(); justEvaluated = false }
        val t = last
        when {
            t is Tok.Num && t.exact != null -> tokens[tokens.lastIndex] = Tok.Num(d.toString())
            t is Tok.Num -> {
                val digits = t.text.count { it.isDigit() }
                if (digits >= MAX_DIGITS) return
                val newText = if (t.text == "0") d.toString() else t.text + d
                tokens[tokens.lastIndex] = t.copy(text = newText)
            }
            t is Tok.RParen || t is Tok.Percent -> { tokens += Tok.Op('*'); tokens += Tok.Num(d.toString()) }
            else -> tokens += Tok.Num(d.toString())
        }
    }

    fun decimal() {
        beginEdit()
        if (justEvaluated) { tokens.clear(); justEvaluated = false }
        val t = last
        when {
            t is Tok.Num && t.exact != null -> tokens[tokens.lastIndex] = Tok.Num("0.")
            t is Tok.Num -> {
                if ('.' in t.text) return
                tokens[tokens.lastIndex] = t.copy(text = if (t.text.isEmpty()) "0." else t.text + ".")
            }
            t is Tok.RParen || t is Tok.Percent -> { tokens += Tok.Op('*'); tokens += Tok.Num("0.") }
            else -> tokens += Tok.Num("0.")
        }
    }

    /** Binary operator: one of + - * / ^ */
    fun op(c: Char) {
        require(c in "+-*/^")
        beginEdit()
        justEvaluated = false
        val t = last
        when {
            t == null -> {
                if (c == '-') tokens += Tok.Num("", negative = true)
                else { tokens += Tok.Num("0"); tokens += Tok.Op(c) }
            }
            t is Tok.Op -> {
                if (c == '-' && t.c in "*/^") tokens += Tok.Num("", negative = true)
                else tokens[tokens.lastIndex] = Tok.Op(c)
            }
            t is Tok.LParen -> if (c == '-') tokens += Tok.Num("", negative = true)
            t is Tok.Sqrt -> Unit
            t is Tok.Num && t.isPending -> Unit
            else -> tokens += Tok.Op(c)
        }
    }

    fun lparen() {
        beginEdit()
        if (justEvaluated) { tokens.clear(); justEvaluated = false }
        val t = last
        if (t is Tok.Num && t.isPending) return
        if (lastIsValueEnd()) tokens += Tok.Op('*')
        tokens += Tok.LParen
    }

    fun rparen() {
        beginEdit()
        if (justEvaluated) return
        if (openParens() <= 0) return
        if (!lastIsValueEnd()) return
        tokens += Tok.RParen
    }

    fun sqrt() {
        beginEdit()
        if (justEvaluated) {
            justEvaluated = false
            tokens.add(0, Tok.Sqrt)
            return
        }
        val t = last
        if (t is Tok.Num && t.isPending) return
        if (lastIsValueEnd()) tokens += Tok.Op('*')
        tokens += Tok.Sqrt
    }

    fun percent() {
        beginEdit()
        val t = last
        if ((t is Tok.Num && !t.isPending) || t is Tok.RParen) {
            justEvaluated = false
            tokens += Tok.Percent
        }
    }

    /** Toggle the sign of the number being entered (or of the result). */
    fun toggleSign() {
        beginEdit()
        val t = last
        when {
            t is Tok.Num && t.exact != null -> tokens[tokens.lastIndex] = t.copy(exact = t.exact.negate())
            t is Tok.Num -> {
                if (t.isPending && t.negative) tokens.removeAt(tokens.lastIndex)
                else tokens[tokens.lastIndex] = t.copy(negative = !t.negative)
            }
            t == null || t is Tok.Op || t is Tok.LParen || t is Tok.Sqrt -> tokens += Tok.Num("", negative = true)
            else -> Unit
        }
    }

    fun backspace() {
        if (justEvaluated) { clear(); return }
        beginEdit()
        val t = last ?: return
        if (t is Tok.Num) {
            if (t.exact != null) {
                val plain = plainForEdit(t.exact)
                val neg = plain.startsWith("-")
                val body = plain.removePrefix("-").dropLast(1)
                tokens[tokens.lastIndex] = Tok.Num(body, neg)
                if (body.isEmpty() && !neg) tokens.removeAt(tokens.lastIndex)
                return
            }
            if (t.text.isNotEmpty()) {
                val body = t.text.dropLast(1)
                if (body.isEmpty() && !t.negative) tokens.removeAt(tokens.lastIndex)
                else tokens[tokens.lastIndex] = t.copy(text = body)
                return
            }
        }
        tokens.removeAt(tokens.lastIndex)
    }

    fun clear() {
        tokens.clear()
        justEvaluated = false
        errorMessage = null
        lastExpression = ""
    }

    /** Commit the expression. Returns true on success. */
    fun equals(): Boolean {
        if (justEvaluated) return true
        val trimmed = trimmedTokens()
        if (trimmed.isEmpty()) return false
        return try {
            val v = evaluate(trimmed)
            val closed = trimmed + List(openParens(trimmed)) { Tok.RParen }
            lastExpression = render(closed)
            tokens.clear()
            tokens += Tok.Num(exact = v)
            justEvaluated = true
            errorMessage = null
            true
        } catch (e: CalcError) {
            errorMessage = e.message
            false
        } catch (e: ArithmeticException) {
            errorMessage = "Ошибка"
            false
        }
    }

    // ---------- memory ----------

    private fun committedValue(): BigDecimal? {
        if (!equals()) return null
        return (tokens.singleOrNull() as? Tok.Num)?.exact
    }

    fun memStore() { committedValue()?.let { memory = it } }
    fun memAdd() { committedValue()?.let { memory = (memory ?: BigDecimal.ZERO).add(it, MC) } }
    fun memSub() { committedValue()?.let { memory = (memory ?: BigDecimal.ZERO).subtract(it, MC) } }
    fun memClear() { memory = null }

    fun memRecall() {
        val m = memory ?: return
        beginEdit()
        if (justEvaluated) { tokens.clear(); justEvaluated = false }
        val t = last
        when {
            t is Tok.Num -> tokens[tokens.lastIndex] = Tok.Num(exact = m)
            t is Tok.RParen || t is Tok.Percent -> { tokens += Tok.Op('*'); tokens += Tok.Num(exact = m) }
            else -> tokens += Tok.Num(exact = m)
        }
    }

    // ---------- display ----------

    fun expressionText(): String = if (tokens.isEmpty()) "0" else render(tokens)

    /** Live result while typing, or null when there is nothing worth previewing. */
    fun preview(): String? {
        if (justEvaluated) return null
        val trimmed = trimmedTokens()
        if (trimmed.none { it is Tok.Op || it is Tok.Sqrt || it is Tok.Percent }) return null
        return try { format(evaluate(trimmed)) } catch (e: Exception) { null }
    }

    fun openParens(list: List<Tok> = tokens): Int =
        list.count { it is Tok.LParen } - list.count { it is Tok.RParen }

    /** Tokens without a trailing incomplete tail (operator, "(", "√", lone minus). */
    private fun trimmedTokens(): List<Tok> {
        val list = tokens.toMutableList()
        while (list.isNotEmpty()) {
            val t = list.last()
            if (t is Tok.Op || t is Tok.LParen || t is Tok.Sqrt || (t is Tok.Num && t.isPending)) list.removeAt(list.lastIndex)
            else break
        }
        return list
    }

    // ---------- evaluation ----------

    private class Value(val v: BigDecimal, val pctRaw: BigDecimal? = null)

    fun evaluate(list: List<Tok>): BigDecimal {
        val p = Parser(list)
        val r = p.expr().v
        if (p.pos < list.size) throw CalcError("Ошибка")
        return r.round(MC)
    }

    private inner class Parser(val t: List<Tok>) {
        var pos = 0
        fun peek(): Tok? = t.getOrNull(pos)

        fun expr(): Value {
            var left = term()
            while (true) {
                val op = peek() as? Tok.Op ?: break
                if (op.c != '+' && op.c != '-') break
                pos++
                val right = term()
                val rv = if (right.pctRaw != null) left.v.multiply(right.pctRaw, MC).divide(HUNDRED, MC) else right.v
                left = Value(if (op.c == '+') left.v.add(rv, MC) else left.v.subtract(rv, MC))
            }
            return left
        }

        fun term(): Value {
            var left = power()
            while (true) {
                val op = peek() as? Tok.Op ?: break
                if (op.c != '*' && op.c != '/') break
                pos++
                val right = power()
                left = if (op.c == '*') Value(left.v.multiply(right.v, MC))
                else {
                    if (right.v.signum() == 0) throw CalcError("Деление на ноль")
                    Value(left.v.divide(right.v, MC))
                }
            }
            return left
        }

        fun power(): Value {
            val base = unary()
            val op = peek() as? Tok.Op
            if (op != null && op.c == '^') {
                pos++
                val exp = power()
                return Value(pow(base.v, exp.v))
            }
            return base
        }

        fun unary(): Value {
            if (peek() is Tok.Sqrt) {
                pos++
                return Value(sqrt(unary().v))
            }
            return postfix()
        }

        fun postfix(): Value {
            var v = primary()
            while (peek() is Tok.Percent) {
                pos++
                v = Value(v.v.divide(HUNDRED, MC), pctRaw = v.v)
            }
            return v
        }

        fun primary(): Value {
            return when (val tok = peek()) {
                is Tok.Num -> { pos++; Value(tok.value()) }
                is Tok.LParen -> {
                    pos++
                    val v = expr()
                    if (peek() is Tok.RParen) pos++
                    else if (pos < t.size) throw CalcError("Ошибка")
                    Value(v.v)
                }
                else -> throw CalcError("Ошибка")
            }
        }
    }

    companion object {
        const val MAX_DIGITS = 15
        val MC = MathContext(34, RoundingMode.HALF_EVEN)
        private val HUNDRED = BigDecimal(100)
        private const val DISPLAY_DIGITS = 12

        fun pow(base: BigDecimal, exp: BigDecimal): BigDecimal {
            val isInt = exp.signum() == 0 || exp.stripTrailingZeros().scale() <= 0
            if (isInt && exp.abs() <= BigDecimal(9999)) {
                val n = exp.toInt()
                if (n == 0) return BigDecimal.ONE
                if (base.signum() == 0) {
                    if (n < 0) throw CalcError("Деление на ноль")
                    return BigDecimal.ZERO
                }
                val p = base.pow(kotlin.math.abs(n), MC)
                return if (n < 0) BigDecimal.ONE.divide(p, MC) else p
            }
            val r = Math.pow(base.toDouble(), exp.toDouble())
            if (r.isNaN() || r.isInfinite()) throw CalcError("Ошибка")
            return BigDecimal(r, MathContext(15))
        }

        fun sqrt(v: BigDecimal): BigDecimal {
            if (v.signum() < 0) throw CalcError("Корень из отрицательного")
            if (v.signum() == 0) return BigDecimal.ZERO
            val d = v.toDouble()
            if (d.isInfinite() || d == 0.0) throw CalcError("Ошибка")
            var x = BigDecimal(Math.sqrt(d))
            val two = BigDecimal(2)
            repeat(6) { x = x.add(v.divide(x, MC), MC).divide(two, MC) }
            return x.round(MC)
        }

        private const val THIN = " "
        private const val NNBSP = " "
        private const val MINUS = "−"

        /** Display formatting of a computed value: grouping, decimal comma, scientific for extremes. */
        fun format(v: BigDecimal): String {
            if (v.signum() == 0) return "0"
            val r = v.round(MathContext(DISPLAY_DIGITS, RoundingMode.HALF_UP)).stripTrailingZeros()
            val exp = r.precision() - r.scale() - 1
            val neg = r.signum() < 0
            val sign = if (neg) MINUS else ""
            if (exp >= 15 || exp < -8) {
                val mant = r.abs().movePointLeft(exp).stripTrailingZeros().toPlainString().replace('.', ',')
                return sign + mant + "×10" + superscript(exp)
            }
            return sign + groupPlain(r.abs().toPlainString())
        }

        /** Group the integer part of a plain number string ("1234.5" -> "1 234,5"). */
        fun groupPlain(plain: String): String {
            val dot = plain.indexOf('.')
            val intPart = if (dot >= 0) plain.substring(0, dot) else plain
            val frac = if (dot >= 0) plain.substring(dot + 1) else null
            val sb = StringBuilder()
            intPart.forEachIndexed { i, ch ->
                if (i > 0 && (intPart.length - i) % 3 == 0) sb.append(NNBSP)
                sb.append(ch)
            }
            if (frac != null) sb.append(',').append(frac)
            return sb.toString()
        }

        private fun superscript(n: Int): String {
            val map = mapOf('0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
                '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹', '-' to '⁻')
            return n.toString().map { map[it] ?: it }.joinToString("")
        }

        /** Plain digits for editing a result with backspace. */
        fun plainForEdit(v: BigDecimal): String =
            v.round(MathContext(MAX_DIGITS, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()

        fun render(list: List<Tok>): String {
            val sb = StringBuilder()
            list.forEachIndexed { i, t ->
                when (t) {
                    is Tok.Num -> {
                        val body = when {
                            t.exact != null -> format(t.exact.abs())
                            else -> groupPlain(t.text)
                        }
                        if (t.isNeg) {
                            val next = list.getOrNull(i + 1)
                            val prev = list.getOrNull(i - 1)
                            val bare = (i == 0 || prev is Tok.LParen) && !(next is Tok.Op && next.c == '^')
                            if (bare) sb.append(MINUS).append(body)
                            else {
                                sb.append('(').append(MINUS).append(body)
                                if (!t.isPending) sb.append(')')
                            }
                        } else sb.append(body)
                    }
                    is Tok.Op -> sb.append(
                        when (t.c) {
                            '+' -> "$THIN+$THIN"
                            '-' -> "$THIN$MINUS$THIN"
                            '*' -> "$THIN×$THIN"
                            '/' -> "$THIN÷$THIN"
                            else -> "^"
                        }
                    )
                    Tok.LParen -> sb.append('(')
                    Tok.RParen -> sb.append(')')
                    Tok.Sqrt -> sb.append('√')
                    Tok.Percent -> sb.append('%')
                }
            }
            return sb.toString()
        }
    }
}
