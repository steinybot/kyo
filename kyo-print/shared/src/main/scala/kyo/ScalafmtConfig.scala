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
    binPack: ScalafmtConfig.BinPack = ScalafmtConfig.BinPack(),
    configStyle: ScalafmtConfig.ConfigStyle = ScalafmtConfig.ConfigStyle(),
    newlines: ScalafmtConfig.Newlines = ScalafmtConfig.Newlines(),
    verticalMultiline: ScalafmtConfig.VerticalMultiline = ScalafmtConfig.VerticalMultiline()
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
        def toDocSource(s: ScalafmtConfig.Newlines.Source): Doc.SourcePolicy = s match
            case ScalafmtConfig.Newlines.Source.Classic => Doc.SourcePolicy.Classic
            case ScalafmtConfig.Newlines.Source.Keep    => Doc.SourcePolicy.Keep
            case ScalafmtConfig.Newlines.Source.Fold    => Doc.SourcePolicy.Fold
            case ScalafmtConfig.Newlines.Source.Unfold  => Doc.SourcePolicy.Unfold
        def toDocBop(b: ScalafmtConfig.Newlines.BeforeOpenParen): Doc.BeforeOpenParen = b match
            case ScalafmtConfig.Newlines.BeforeOpenParen.Fold   => Doc.BeforeOpenParen.Fold
            case ScalafmtConfig.Newlines.BeforeOpenParen.Unfold => Doc.BeforeOpenParen.Unfold
            case ScalafmtConfig.Newlines.BeforeOpenParen.Keep   => Doc.BeforeOpenParen.Keep
        def toDocBclp(b: ScalafmtConfig.Newlines.BeforeCurlyLambdaParams): Doc.BeforeCurlyLambdaParams = b match
            case ScalafmtConfig.Newlines.BeforeCurlyLambdaParams.Never                 => Doc.BeforeCurlyLambdaParams.Never
            case ScalafmtConfig.Newlines.BeforeCurlyLambdaParams.Always                => Doc.BeforeCurlyLambdaParams.Always
            case ScalafmtConfig.Newlines.BeforeCurlyLambdaParams.Multiline             => Doc.BeforeCurlyLambdaParams.Multiline
            case ScalafmtConfig.Newlines.BeforeCurlyLambdaParams.MultilineWithCaseOnly => Doc.BeforeCurlyLambdaParams.MultilineWithCaseOnly
        def toDocAclp(a: ScalafmtConfig.Newlines.AfterCurlyLambdaParams): Doc.AfterCurlyLambdaParams = a match
            case ScalafmtConfig.Newlines.AfterCurlyLambdaParams.Never  => Doc.AfterCurlyLambdaParams.Never
            case ScalafmtConfig.Newlines.AfterCurlyLambdaParams.Always => Doc.AfterCurlyLambdaParams.Always
            case ScalafmtConfig.Newlines.AfterCurlyLambdaParams.Keep   => Doc.AfterCurlyLambdaParams.Keep
            case ScalafmtConfig.Newlines.AfterCurlyLambdaParams.Squash => Doc.AfterCurlyLambdaParams.Squash
        site match
            case Doc.ClauseSite.Call =>
                Doc.ClausePolicy(
                    site = site,
                    indent = indent.callSite,
                    dangling = danglingParentheses.callSite,
                    align = align.openParenCallSite,
                    binPack = siteMode(binPack.callSite),
                    configStyle = configStyle.callSite,
                    source = toDocSource(newlines.source),
                    beforeOpenParen = newlines.beforeOpenParenCallSite.map(toDocBop)
                )
            case Doc.ClauseSite.Defn =>
                val forceBefore = verticalMultiline.newlinesBeforeImplicitKW ||
                    newlines.implicitParamListModifierForce.contains(ScalafmtConfig.Newlines.BeforeAfter.Before)
                val forceAfter = verticalMultiline.newlinesAfterImplicitKW ||
                    newlines.implicitParamListModifierForce.contains(ScalafmtConfig.Newlines.BeforeAfter.After)
                Doc.ClausePolicy(
                    site = site,
                    indent = indent.defnSite,
                    dangling = danglingParentheses.defnSite,
                    align = align.openParenDefnSite,
                    binPack = siteMode(binPack.defnSite),
                    configStyle = configStyle.defnSite,
                    source = toDocSource(newlines.source),
                    beforeOpenParen = newlines.beforeOpenParenDefnSite.map(toDocBop),
                    verticalMultiline =
                        if verticalMultiline.atDefnSite then
                            Some(Doc.VerticalMultiline(
                                atDefnSite = verticalMultiline.atDefnSite,
                                arityThreshold = verticalMultiline.arityThreshold,
                                newlineBeforeOpenParen = verticalMultiline.newlineBeforeOpenParen,
                                newlineAfterOpenParen = verticalMultiline.newlineAfterOpenParen,
                                newlinesAfterImplicitKW = forceAfter,
                                newlinesBeforeImplicitKW = forceBefore,
                                closeParenNewline = verticalMultiline.closeParenNewline,
                                includeImplicitKw = verticalMultiline.includeImplicitKw,
                                includeApplyKw = verticalMultiline.includeApplyKw,
                                keepSymbols = verticalMultiline.keepSymbols
                            ))
                        else None
                )
            case Doc.ClauseSite.Extend =>
                Doc.ClausePolicy(
                    site = site,
                    indent = indent.extendSite,
                    dangling = false,
                    align = false,
                    binPack = ctorMode(binPack.parentConstructors),
                    configStyle = false,
                    source = toDocSource(newlines.source),
                    beforeOpenParen = None
                )
            case Doc.ClauseSite.Lambda =>
                Doc.ClausePolicy(
                    site = site,
                    indent = indent.callSite,
                    dangling = true,
                    align = false,
                    binPack = Doc.BinPackMode.Never,
                    configStyle = false,
                    source = toDocSource(newlines.source),
                    beforeOpenParen = None,
                    beforeCurlyLambdaParams = toDocBclp(newlines.beforeCurlyLambdaParams),
                    afterCurlyLambdaParams = toDocAclp(newlines.afterCurlyLambdaParams)
                )
        end match
    end clausePolicy
end ScalafmtConfig

object ScalafmtConfig:

    /** The configuration matching the scalafmt `default` preset. */
    val default: ScalafmtConfig = ScalafmtConfig()

    /** The configuration matching the scalafmt `IntelliJ` preset. */
    val intellij: ScalafmtConfig = ScalafmtConfig(
        indent = Indent(callSite = 2, defnSite = 2),
        configStyle = ConfigStyle(false)
    )

    /** The configuration matching the scalafmt `Scala.js` preset. */
    val scalaJs: ScalafmtConfig = ScalafmtConfig(
        indent = Indent(callSite = 4, defnSite = 4),
        danglingParentheses = DanglingParentheses(false),
        binPack = BinPack(
            callSite = BinPack.Site.Always,
            defnSite = BinPack.Site.Always,
            parentConstructors = BinPack.ParentCtors.Always
        ),
        newlines = Newlines(
            avoidInResultType = true,
            neverBeforeJsNative = true,
            sometimesBeforeColonInMethodReturnType = false
        )
    )

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

    object DanglingParentheses:
        def apply(preset: Boolean): DanglingParentheses =
            DanglingParentheses(callSite = preset, defnSite = preset)
    end DanglingParentheses

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

    /** Whether config-style formatting is preferred at a site.
      *
      * Mirrors `newlines.configStyleCallSite.prefer` and
      * `newlines.configStyleDefnSite.prefer`, the v3.8.2 replacements for the
      * single legacy `optIn.configStyleArguments` flag whose value they fall
      * back to (and which defaulted to true). When preferred and the engine
      * detects config style in the source, a clause renders with a break
      * after the opening delimiter, one element per line, and a break before
      * the closing delimiter, regardless of the dangling or bin-packing
      * options.
      */
    final case class ConfigStyle(
        callSite: Boolean = true,
        defnSite: Boolean = true
    ) derives CanEqual

    object ConfigStyle:
        def apply(prefer: Boolean): ConfigStyle =
            ConfigStyle(callSite = prefer, defnSite = prefer)
    end ConfigStyle

    /** Controls line break placement, source newline interpretation, and
      * construct-specific break policies.
      *
      * Mirrors the `newlines` section of scalafmt.
      */
    final case class Newlines(
        source: Newlines.Source = Newlines.Source.Classic,
        beforeOpenParenCallSite: Option[Newlines.BeforeOpenParen] = None,
        beforeOpenParenDefnSite: Option[Newlines.BeforeOpenParen] = None,
        sometimesBeforeColonInMethodReturnType: Boolean = true,
        beforeCurlyLambdaParams: Newlines.BeforeCurlyLambdaParams = Newlines.BeforeCurlyLambdaParams.Never,
        afterCurlyLambdaParams: Newlines.AfterCurlyLambdaParams = Newlines.AfterCurlyLambdaParams.Never,
        beforeTemplateBodyIfBreakInParentCtors: Boolean = false,
        alwaysBeforeElseAfterCurlyIf: Boolean = false,
        beforeMultiline: Option[Newlines.Source] = None,
        forceBeforeMultilineAssign: Newlines.ForceBeforeMultilineAssign = Newlines.ForceBeforeMultilineAssign.Never,
        avoidInResultType: Boolean = false,
        avoidAfterYield: Boolean = true,
        neverBeforeJsNative: Boolean = false,
        implicitParamListModifierForce: List[Newlines.BeforeAfter] = Nil
    ) derives CanEqual

    object Newlines:

        /** Whether an implicit keyword modifier forces breaks before or after. */
        enum BeforeAfter derives CanEqual:
            case Before, After
        end BeforeAfter

        /** Interpretation of line breaks present in the input source.
          *
          * `Classic` folds single-delimiter breaks into spaces and preserves
          * breaks at both delimiters. `Keep` preserves existing line breaks
          * wherever valid. `Fold` ignores source breaks and collapses into
          * horizontal layout when width permits. `Unfold` ignores source
          * breaks and prefers vertical layout.
          */
        enum Source derives CanEqual:
            case Classic, Keep, Fold, Unfold
        end Source

        /** Break policy before the opening parenthesis of a parameter group. */
        enum BeforeOpenParen derives CanEqual:
            case Fold, Unfold, Keep
        end BeforeOpenParen

        /** Break policy between the opening curly brace and lambda parameters. */
        enum BeforeCurlyLambdaParams derives CanEqual:
            case Never, Always, Multiline, MultilineWithCaseOnly
        end BeforeCurlyLambdaParams

        /** Break and blank-line policy following the arrow of a curly lambda. */
        enum AfterCurlyLambdaParams derives CanEqual:
            case Never, Always, Keep, Squash
        end AfterCurlyLambdaParams

        /** Whether to force a line break before the body of a multiline assignment. */
        enum ForceBeforeMultilineAssign derives CanEqual:
            case Never, Any, Def, AnyMember, TopMember
        end ForceBeforeMultilineAssign

    end Newlines

    /** Definition-site multiline parameter list formatting.
      *
      * Mirrors the `verticalMultiline` section of scalafmt: when enabled at
      * definition sites and a method's parameter count meets or exceeds
      * `arityThreshold` or its signature overflows `maxColumn`, parameter
      * groups break vertically with one parameter per line indented by
      * `indent.defnSite`. Group delimiters `)(` and dangling closing
      * delimiters are indented two spaces less than `indent.defnSite`.
      */
    final case class VerticalMultiline(
        atDefnSite: Boolean = false,
        arityThreshold: Int = 100,
        newlineBeforeOpenParen: Boolean = false,
        newlineAfterOpenParen: Boolean = false,
        newlinesAfterImplicitKW: Boolean = false,
        newlinesBeforeImplicitKW: Boolean = false,
        closeParenNewline: Boolean = false,
        includeImplicitKw: Boolean = false,
        includeApplyKw: Boolean = false,
        keepSymbols: Boolean = false
    ) derives CanEqual:
        def newlineAfterImplicitKW: Boolean  = newlinesAfterImplicitKW
        def newlineBeforeImplicitKW: Boolean = newlinesBeforeImplicitKW
        def atDefinitionSite: Boolean        = atDefnSite
    end VerticalMultiline

    object VerticalMultiline:
        def apply(
            atDefnSite: Boolean = false,
            arityThreshold: Int = 100,
            newlineBeforeOpenParen: Boolean = false,
            newlineAfterOpenParen: Boolean = false,
            newlinesAfterImplicitKW: Boolean = false,
            newlinesBeforeImplicitKW: Boolean = false,
            closeParenNewline: Boolean = false,
            includeImplicitKw: Boolean = false,
            includeApplyKw: Boolean = false,
            keepSymbols: Boolean = false
        ): VerticalMultiline =
            new VerticalMultiline(
                atDefnSite = atDefnSite,
                arityThreshold = arityThreshold,
                newlineBeforeOpenParen = newlineBeforeOpenParen,
                newlineAfterOpenParen = newlineAfterOpenParen,
                newlinesAfterImplicitKW = newlinesAfterImplicitKW,
                newlinesBeforeImplicitKW = newlinesBeforeImplicitKW,
                closeParenNewline = closeParenNewline,
                includeImplicitKw = includeImplicitKw,
                includeApplyKw = includeApplyKw,
                keepSymbols = keepSymbols
            )
    end VerticalMultiline

end ScalafmtConfig
