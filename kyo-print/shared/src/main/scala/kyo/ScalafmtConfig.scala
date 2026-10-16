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
  * bin-packing disabled at every site.
  *
  * `clausePolicy` resolves a site into the policy the scalafmt parity engine
  * applies; document builders call it so every layout decision stays at layout
  * time.
  *
  * @see [[https://scalameta.github.io/scalafmt/docs/configuration.html scalafmt configuration reference]]
  */
final case class ScalafmtConfig(
    maxColumn: Int = 80,
    indent: ScalafmtConfig.Indent = ScalafmtConfig.Indent(),
    danglingParentheses: ScalafmtConfig.DanglingParentheses = ScalafmtConfig.DanglingParentheses(),
    align: ScalafmtConfig.Align = ScalafmtConfig.Align(),
    binPack: ScalafmtConfig.BinPack = ScalafmtConfig.BinPack()
) derives CanEqual:

    /** Resolves the clause policy the parity engine applies at a site.
      *
      * Each site reads its own scalafmt options: call and definition sites
      * their indentation, dangling, alignment, and bin-packing entries, and
      * template parent clauses the extend-site indentation and the
      * `binPack.parentConstructors` mode (templates have no closing delimiter
      * to dangle and no parenthesis to align by). Builders call this when
      * constructing a [[Doc.Clause]] so every layout decision stays at layout
      * time.
      */
    def clausePolicy(site: Doc.ClauseSite): Doc.ClausePolicy =
        import ScalafmtConfig.BinPack
        import ScalafmtConfig.BinPack.Site
        def siteMode(mode: Site): Doc.BinPackMode = mode match
            case Site.Never   => Doc.BinPackMode.Never
            case Site.Always  => Doc.BinPackMode.Always
            case Site.Oneline => Doc.BinPackMode.Oneline
        def ctorMode(mode: BinPack.ParentCtors): Doc.BinPackMode = mode match
            case BinPack.ParentCtors.Never                   => Doc.BinPackMode.Never
            case BinPack.ParentCtors.Always                  => Doc.BinPackMode.Always
            case BinPack.ParentCtors.Oneline                 => Doc.BinPackMode.Oneline
            case BinPack.ParentCtors.OnelineIfPrimaryOneline => Doc.BinPackMode.OnelineIfPrimaryOneline
        site match
            case Doc.ClauseSite.Call =>
                Doc.ClausePolicy(
                    site = site,
                    indent = indent.callSite,
                    dangling = danglingParentheses.callSite,
                    align = align.openParenCallSite,
                    binPack = siteMode(binPack.callSite)
                )
            case Doc.ClauseSite.Defn =>
                Doc.ClausePolicy(
                    site = site,
                    indent = indent.defnSite,
                    dangling = danglingParentheses.defnSite,
                    align = align.openParenDefnSite,
                    binPack = siteMode(binPack.defnSite)
                )
            case Doc.ClauseSite.Extend =>
                Doc.ClausePolicy(
                    site = site,
                    indent = indent.extendSite,
                    dangling = false,
                    align = false,
                    binPack = ctorMode(binPack.parentConstructors)
                )
        end match
    end clausePolicy
end ScalafmtConfig

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

    /** Argument packing strategies, one per clause site.
      *
      * Mirrors the `binPack` section of scalafmt: `callSite` and `defnSite`
      * pack method call arguments and definition parameters, and
      * `parentConstructors` packs template parents (`extends` / `with`).
      * All three default to no packing, matching the scalafmt default preset
      * (`newlines.source = classic` resolves the upstream `parentConstructors
      * = source` default to `Never`).
      */
    final case class BinPack(
        callSite: ScalafmtConfig.BinPack.Site = BinPack.Site.Never,
        defnSite: ScalafmtConfig.BinPack.Site = BinPack.Site.Never,
        parentConstructors: ScalafmtConfig.BinPack.ParentCtors = BinPack.ParentCtors.Never
    ) derives CanEqual

    object BinPack:

        /** Selects how arguments of a broken call or definition clause are
          * distributed across lines.
          *
          * `Never` disables packing: once the clause breaks, every argument
          * occupies its own line. `Always` fits as many arguments per line as
          * the column budget allows, letting a multiline argument share its
          * last line with later arguments. `Oneline` packs like `Always` but
          * keeps multiline arguments isolated on their own lines, breaking
          * after the opening delimiter when the first argument is multiline.
          */
        enum Site derives CanEqual:
            case Never, Always, Oneline
        end Site

        /** Selects how the parents of a broken template are distributed across
          * lines.
          *
          * `Never` gives every parent its own line. `Always` packs parents,
          * letting a multiline parent share its last line with later parents.
          * `Oneline` packs parents only when every parent fits on a single
          * line, and otherwise falls back to one parent per line.
          * `OnelineIfPrimaryOneline` applies `Oneline` when the template's
          * parameter clause renders on a single line and `Never` otherwise.
          *
          * The upstream `source` and `keep` values resolve through
          * `newlines.source`, which this module does not yet model; under the
          * default classic source they behave as `Never`, which is the
          * default here.
          */
        enum ParentCtors derives CanEqual:
            case Never, Always, Oneline, OnelineIfPrimaryOneline
        end ParentCtors

    end BinPack

end ScalafmtConfig
