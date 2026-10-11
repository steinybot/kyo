package kyo

/** Layout configuration modeled on the documented options of the scalafmt code
  * formatter.
  *
  * Holds the subset of scalafmt options that govern how argument lists, parameter
  * lists, and template bodies break across lines. Fixtures built as `Doc < Env[ScalafmtConfig]`
  * can be rendered against different configurations to validate that the layout
  * combinators of this module reproduce the styles documented by scalafmt.
  *
  * Defaults mirror the scalafmt `default` preset: a maximum column of 80,
  * call sites indented by 2 spaces, definition and extension sites by 4,
  * dangling closing parentheses enabled, no open-parenthesis alignment, and
  * bin-packing disabled.
  *
  * @see [[https://scalameta.github.io/scalafmt/docs/configuration.html scalafmt configuration reference]]
  */
final case class ScalafmtConfig(
    maxColumn: Int = 80,
    indent: ScalafmtConfig.Indent = ScalafmtConfig.Indent(),
    danglingParentheses: ScalafmtConfig.DanglingParentheses = ScalafmtConfig.DanglingParentheses(),
    align: ScalafmtConfig.Align = ScalafmtConfig.Align(),
    binPack: ScalafmtConfig.BinPack = ScalafmtConfig.BinPack()
) derives CanEqual

object ScalafmtConfig:

    /** The configuration matching the scalafmt `default` preset. */
    val default: ScalafmtConfig = ScalafmtConfig()

    /** Indentation widths applied when a construct breaks across lines.
      *
      * Mirrors the `indent` section of scalafmt: `callSite` controls the
      * indentation of method call arguments, `defnSite` the indentation of
      * definition parameters, and `extendSite` the indentation of `extends`
      * and `derives` clauses in templates.
      */
    final case class Indent(
        callSite: Int = 2,
        defnSite: Int = 4,
        extendSite: Int = 4
    ) derives CanEqual

    /** Whether a closing parenthesis goes on its own line when the enclosing
      * clause breaks.
      *
      * Mirrors the `danglingParentheses` section of scalafmt. When enabled and
      * the clause does not fit on a single line, the closing delimiter is
      * placed on a new line aligned with the clause opening. When disabled,
      * the closing delimiter sticks to the final argument.
      */
    final case class DanglingParentheses(
        callSite: Boolean = true,
        defnSite: Boolean = true
    ) derives CanEqual

    /** Whether line breaks align to the column of the opening parenthesis.
      *
      * Mirrors the open-parenthesis entries of the scalafmt `align` section.
      * When enabled and the clause breaks, continuation lines are aligned
      * under the opening delimiter instead of receiving fixed indentation.
      * Both flags default to `false`, matching scalafmt since v1.6.
      */
    final case class Align(
        openParenCallSite: Boolean = false,
        openParenDefnSite: Boolean = false
    ) derives CanEqual

    /** Argument packing strategy at method call sites.
      *
      * Mirrors the `binPack` section of scalafmt, restricted to `callSite`.
      * Controls whether the arguments of a broken call are packed greedily
      * onto each line or kept one per line.
      */
    final case class BinPack(
        callSite: ScalafmtConfig.BinPack.Site = BinPack.Site.Never
    ) derives CanEqual

    object BinPack:

        /** Selects how arguments of a broken clause are distributed across lines.
          *
          * `Never` disables packing: once the clause breaks, every argument
          * occupies its own line. `Always` fits as many arguments per line as
          * the column budget allows. `Oneline` packs like `Always` but keeps
          * multiline arguments isolated on their own lines.
          */
        enum Site derives CanEqual:
            case Never, Always, Oneline
        end Site

    end BinPack

end ScalafmtConfig
