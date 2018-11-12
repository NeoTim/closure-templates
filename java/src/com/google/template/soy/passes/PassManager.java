/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.passes;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.base.internal.SoyFileKind;
import com.google.template.soy.base.internal.TriState;
import com.google.template.soy.conformance.ValidatedConformanceConfig;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.logging.ValidatedLoggingConfig;
import com.google.template.soy.parsepasses.contextautoesc.ContextualAutoescaper;
import com.google.template.soy.parsepasses.contextautoesc.DerivedTemplateUtils;
import com.google.template.soy.shared.SoyGeneralOptions;
import com.google.template.soy.shared.restricted.SoyPrintDirective;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.TemplateDelegateNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.TemplateRegistry;
import com.google.template.soy.types.SoyTypeRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;

/**
 * Configures all compiler passes.
 *
 * <p>This arranges all compiler passes into four phases.
 *
 * <ul>
 *   <li>The single file passes. This includes AST rewriting passes such as {@link HtmlRewritePass}
 *       and {@link RewriteGendersPass} and other kinds of validation that doesn't require
 *       information about the full file set.
 *   <li>Cross template checking passes. This includes AST validation passes like the {@link
 *       CheckVisibilityPass}. Passes should run here if they need to check the relationships
 *       between templates.
 *   <li>The autoescaper. This runs in its own special phase because it can do special things like
 *       create synthetic templates and add them to the tree.
 *   <li>Simplification passes. This includes tree simplification passes like the optimizer. These
 *       should run last so that they can simplify code generated by any earlier pass.
 * </ul>
 *
 * <p>The reason things have been divided in this way is partially to create consistency and also to
 * enable other compiler features. For example, for in process (server side) compilation we can
 * cache the results of the single file passes to speed up edit-refresh flows. Also, theoretically,
 * we could run the single file passes for each file in parallel.
 *
 * <p>A note on ordering. There is no real structure to the ordering of the passes beyond what is
 * documented in comments. Many passes do rely on running before/after a different pass (e.g. {@link
 * ResolveExpressionTypesPass} needs to run after {@link ResolveNamesPass}), but there isn't any
 * dependency system in place.
 */
public final class PassManager {
  private final ImmutableList<CompilerFilePass> singleFilePasses;
  private final ImmutableList<CompilerFileSetPass> crossTemplateCheckingPasses;
  private final ImmutableList<CompilerFileSetPass> afterAutoescapePasses;
  private final SoyTypeRegistry registry;
  private final ErrorReporter errorReporter;
  private final SoyGeneralOptions options;
  private final boolean desugarHtmlNodes;
  @Nullable private final ContextualAutoescaper autoescaper;

  private PassManager(Builder builder) {
    checkArgument(!builder.conformanceOnly);

    this.registry = checkNotNull(builder.registry);
    this.errorReporter = checkNotNull(builder.errorReporter);
    this.options = checkNotNull(builder.opts);
    boolean allowUnknownGlobals = builder.allowUnknownGlobals;
    boolean disableAllTypeChecking = builder.disableAllTypeChecking;
    this.desugarHtmlNodes = builder.desugarHtmlNodes;
    this.autoescaper =
        builder.autoescaperEnabled ? new ContextualAutoescaper(builder.soyPrintDirectives) : null;

    // Single file passes
    // These passes perform tree rewriting and all compiler checks that don't require information
    // about callees.
    // Note that we try to run all of the single file passes to report as many errors as possible,
    // meaning that errors reported in earlier passes do not prevent running subsequent passes.

    ImmutableList.Builder<CompilerFilePass> singleFilePassesBuilder =
        ImmutableList.<CompilerFilePass>builder()
            .add(new HtmlRewritePass(errorReporter))
            .add(new BasicHtmlValidationPass(errorReporter))
            // The check conformance pass needs to run on the rewritten html nodes, so it must
            // run after HtmlRewritePass
            .add(new SoyConformancePass(builder.conformanceConfig, errorReporter))
            // needs to run after htmlrewriting, before resolvenames and autoescaping
            .add(new ContentSecurityPolicyNonceInjectionPass(errorReporter))
            // Needs to run after HtmlRewritePass since it produces the HtmlTagNodes that we use to
            // create placeholders.
            .add(new InsertMsgPlaceholderNodesPass(errorReporter))
            .add(new RewriteRemaindersPass((errorReporter)))
            .add(new RewriteGenderMsgsPass(errorReporter))
            // Needs to come after any pass that manipulates msg placeholders.
            .add(new CalculateMsgSubstitutionInfoPass(errorReporter))
            .add(new CheckNonEmptyMsgNodesPass(errorReporter))
            // Needs to run after inserting msg placeholders to ensure that genders="..."
            // expressions do not introduce extra placeholders for call and print nodes.
            .add(new StrictHtmlValidationPass(errorReporter))
            .add(new RewriteGlobalsPass(registry, options.getCompileTimeGlobals(), errorReporter))
            // needs to happen after rewrite globals
            .add(new XidPass(errorReporter))
            // Needs to be before ResolveNamesPass.
            .add(new V1ExpressionPass(builder.allowV1Expression, errorReporter))
            .add(new ResolveNamesPass(errorReporter))
            // needs to be after ResolveNames and MsgsPass
            .add(new MsgWithIdFunctionPass(errorReporter));
    if (builder.addHtmlAttributesForDebugging) {
      // needs to run after MsgsPass (so we don't mess up the auto placeholder naming algorithm) and
      // before ResolveExpressionTypesPass (since we insert expressions).
      singleFilePassesBuilder.add(new AddDebugAttributesPass());
    }
    if (!disableAllTypeChecking) {
      singleFilePassesBuilder.add(new CheckDeclaredTypesPass(errorReporter));
      VeLogValidator veLogValidator = new VeLogValidator(builder.loggingConfig, errorReporter);
      singleFilePassesBuilder.add(
          new ResolveExpressionTypesPass(registry, errorReporter, veLogValidator));
      // needs to run after both resolve types and htmlrewrite pass
      singleFilePassesBuilder.add(new VeLogValidationPass(errorReporter, veLogValidator));
    }
    singleFilePassesBuilder.add(new ResolvePackageRelativeCssNamesPass(errorReporter));
    if (!allowUnknownGlobals) {
      // Must come after RewriteGlobalsPass since that is when values are substituted.
      // We should also run after the ResolveNamesPass which checks for global/param ambiguity and
      // may issue better error messages.
      singleFilePassesBuilder.add(new CheckGlobalsPass(errorReporter));
    }
    singleFilePassesBuilder.add(new ValidateAliasesPass(registry, errorReporter, options));
    if (!disableAllTypeChecking) {
      // Must run after ResolveExpressionTypesPass, which adds the SoyProtoType info.
      // TODO(lukes): both of these are really about type checking, they should be part of
      // ResolveExpressionTypesPass
      singleFilePassesBuilder.add(new CheckProtoInitCallsPass(errorReporter));
    }
    // If requiring strict autoescaping, check and enforce it.
    if (options.isStrictAutoescapingRequired() == TriState.ENABLED) {
      singleFilePassesBuilder.add(new AssertStrictAutoescapingPass(errorReporter));
    }
    // Needs to run after HtmlRewritePass and StrictHtmlValidationPass (for single root validation).
    singleFilePassesBuilder.add(new SoyElementPass(errorReporter));

    this.singleFilePasses = singleFilePassesBuilder.build();

    // Cross template checking passes

    // Fileset passes run on the whole tree and should be reserved for checks that need transitive
    // call information (or full delegate sets).
    // Notably, the results of these passes cannot be cached in the AST cache.  So minimize their
    // use.
    ImmutableList.Builder<CompilerFileSetPass> beforeAutoescaperFileSetPassBuilder =
        ImmutableList.<CompilerFileSetPass>builder()
            .add(new CheckTemplateHeaderVarsPass(errorReporter));
    if (!disableAllTypeChecking) {
      beforeAutoescaperFileSetPassBuilder.add(new CheckTemplateCallsPass(errorReporter));
    }
    beforeAutoescaperFileSetPassBuilder
        .add(new CheckTemplateVisibilityPass(errorReporter))
        .add(new CheckDelegatesPass(errorReporter))
        // Could run ~anywhere, needs to be a fileset pass to validate deprecated-noncontextual
        // calls.  Make this a singlefile pass when deprecated-noncontextual is dead.
        .add(new CheckEscapingSanityFileSetPass(errorReporter));
    // If disallowing external calls, perform the check.
    if (options.allowExternalCalls() == TriState.DISABLED) {
      beforeAutoescaperFileSetPassBuilder.add(new StrictDepsPass(errorReporter));
    }
    // if htmlrewriting is enabled, don't desugar because later passes want the nodes
    // we need to run this here, before the autoescaper because the autoescaper may choke on lots
    // of little raw text nodes.  The desguaring pass and rewrite passes above may produce empty
    // raw text nodes and lots of consecutive raw text nodes.  This will eliminate them
    beforeAutoescaperFileSetPassBuilder.add(new CombineConsecutiveRawTextNodesPass());

    this.crossTemplateCheckingPasses = beforeAutoescaperFileSetPassBuilder.build();

    // Simplification passes

    ImmutableList.Builder<CompilerFileSetPass> afterAutoescapePasses = ImmutableList.builder();
    if (!disableAllTypeChecking && autoescaper != null) {
      afterAutoescapePasses.add(new CheckBadContextualUsagePass(errorReporter));
    }
    if (desugarHtmlNodes) {
      // always desugar before the end since the backends (besides incremental dom) cannot handle
      // the nodes.
      afterAutoescapePasses.add(new DesugarHtmlNodesPass());
    }
    // TODO(lukes): there should only be one way to disable the optimizer, not 2
    if (builder.optimize && options.isOptimizerEnabled()) {
      afterAutoescapePasses.add(new OptimizationPass());
    }
    // A number of the passes above (desuagar, htmlrewrite), may chop up raw text nodes in ways
    // that can be later stitched together.  Do that here.  This also drops empty RawTextNodes,
    // which some of the backends don't like (incremental dom can generate bad code in some cases).
    afterAutoescapePasses.add(new CombineConsecutiveRawTextNodesPass());
    this.afterAutoescapePasses = afterAutoescapePasses.build();
  }

  // This constructor is just used for the conformanceOnly mode, the boolean parameter is just to
  // make it a unique overload.
  private PassManager(Builder builder, boolean unused) {
    checkArgument(builder.conformanceOnly);
    this.registry = null;
    this.errorReporter = checkNotNull(builder.errorReporter);
    this.options = null;
    this.desugarHtmlNodes = false;
    this.autoescaper = null;
    this.singleFilePasses =
        ImmutableList.<CompilerFilePass>builder()
            .add(new HtmlRewritePass(errorReporter))
            // The check conformance pass needs to run on the rewritten html nodes, so it must
            // run after HtmlRewritePass
            .add(new SoyConformancePass(builder.conformanceConfig, errorReporter))
            .build();
    this.crossTemplateCheckingPasses = ImmutableList.of();
    this.afterAutoescapePasses = ImmutableList.of();
  }

  public void runSingleFilePasses(SoyFileNode file, IdGenerator nodeIdGen) {
    boolean isSourceFile = file.getSoyFileKind() == SoyFileKind.SRC;
    for (CompilerFilePass pass : singleFilePasses) {
      // All passes run on source files, but only some passes should run on deps.
      if (isSourceFile || pass.shouldRunOnDepsAndIndirectDeps()) {
        pass.run(file, nodeIdGen);
      }
    }
  }

  /**
   * Runs all the fileset passes including the autoescaper and optimization passes if configured.
   *
   * @return a fully populated TemplateRegistry
   */
  @CheckReturnValue
  public TemplateRegistry runWholeFilesetPasses(SoyFileSetNode soyTree) {
    TemplateRegistry templateRegistry = new TemplateRegistry(soyTree, errorReporter);
    ImmutableList<SoyFileNode> sourceFiles = soyTree.getSourceFiles();
    IdGenerator idGenerator = soyTree.getNodeIdGenerator();
    for (CompilerFileSetPass pass : crossTemplateCheckingPasses) {
      pass.run(sourceFiles, idGenerator, templateRegistry);
    }
    if (errorReporter.hasErrors()) {
      return templateRegistry;
    }
    if (autoescaper != null) {
      boolean newTemplatesAdded = doContextualEscaping(soyTree);
      if (errorReporter.hasErrors()) {
        return templateRegistry;
      }

      // contextual autoescaping may actually add new templates to the tree so check if we need to
      // reconstruct the registry.  Note. constructing the registry is kind of expensive so we
      // should avoid doing it if possible
      if (newTemplatesAdded) {
        templateRegistry = new TemplateRegistry(soyTree, errorReporter);
      }
    }
    for (CompilerFileSetPass pass : afterAutoescapePasses) {
      pass.run(sourceFiles, idGenerator, templateRegistry);
    }
    return templateRegistry;
  }

  /** Runs the autoescaper and returns whether or not new contextual templates have been added. */
  private boolean doContextualEscaping(SoyFileSetNode soyTree) {
    List<TemplateNode> extraTemplates = autoescaper.rewrite(soyTree, errorReporter);
    // TODO: Run the redundant template remover here and rename after CL 16642341 is in.
    if (!extraTemplates.isEmpty()) {
      // TODO: pull out somewhere else.  Ideally do the merge as part of the redundant template
      // removal.
      Map<String, SoyFileNode> containingFile = new HashMap<>();
      for (SoyFileNode fileNode : soyTree.getChildren()) {
        for (TemplateNode templateNode : fileNode.getChildren()) {
          String name =
              templateNode instanceof TemplateDelegateNode
                  ? ((TemplateDelegateNode) templateNode).getDelTemplateName()
                  : templateNode.getTemplateName();
          containingFile.put(DerivedTemplateUtils.getBaseName(name), fileNode);
        }
      }
      for (TemplateNode extraTemplate : extraTemplates) {
        String name =
            extraTemplate instanceof TemplateDelegateNode
                ? ((TemplateDelegateNode) extraTemplate).getDelTemplateName()
                : extraTemplate.getTemplateName();
        containingFile.get(DerivedTemplateUtils.getBaseName(name)).addChild(extraTemplate);
      }
      return true;
    }
    return false;
  }

  /** A builder for configuring the pass manager. */
  public static final class Builder {
    private SoyTypeRegistry registry;
    private ImmutableMap<String, ? extends SoyPrintDirective> soyPrintDirectives;
    private ErrorReporter errorReporter;
    private SoyGeneralOptions opts;
    private boolean allowUnknownGlobals;
    private boolean allowV1Expression;
    private boolean disableAllTypeChecking;
    private boolean desugarHtmlNodes = true;
    private boolean optimize = true;
    private ValidatedConformanceConfig conformanceConfig = ValidatedConformanceConfig.EMPTY;
    private ValidatedLoggingConfig loggingConfig = ValidatedLoggingConfig.EMPTY;
    private boolean autoescaperEnabled = true;
    private boolean addHtmlAttributesForDebugging = true;
    private boolean conformanceOnly = false;

    public Builder setErrorReporter(ErrorReporter errorReporter) {
      this.errorReporter = checkNotNull(errorReporter);
      return this;
    }

    public Builder setSoyPrintDirectiveMap(
        ImmutableMap<String, ? extends SoyPrintDirective> printDirectives) {
      this.soyPrintDirectives = checkNotNull(printDirectives);
      return this;
    }

    public Builder setTypeRegistry(SoyTypeRegistry registry) {
      this.registry = checkNotNull(registry);
      return this;
    }

    public Builder setGeneralOptions(SoyGeneralOptions opts) {
      this.opts = opts;
      return this;
    }

    public Builder setConformanceOnly(boolean conformanceOnly) {
      this.conformanceOnly = conformanceOnly;
      return this;
    }

    /**
     * Disables all the passes which enforce and rely on type information.
     *
     * <p>This should only be used for things like message extraction which doesn't tend to be
     * configured with a type registry.
     */
    public Builder disableAllTypeChecking() {
      this.disableAllTypeChecking = true;
      return this;
    }

    /**
     * Allows unknown global references.
     *
     * <p>This option is only available for backwards compatibility with legacy js only templates
     * and for parseinfo generation.
     */
    public Builder allowUnknownGlobals() {
      this.allowUnknownGlobals = true;
      return this;
    }

    /**
     * Allows v1Expression().
     *
     * <p>This option is only available for backwards compatibility with legacy JS only templates.
     */
    public Builder allowV1Expression() {
      this.allowV1Expression = true;
      return this;
    }

    /**
     * Whether to turn all the html nodes back into raw text nodes before code generation.
     *
     * <p>The default is {@code true}.
     */
    public Builder desugarHtmlNodes(boolean desugarHtmlNodes) {
      this.desugarHtmlNodes = desugarHtmlNodes;
      return this;
    }

    /**
     * Whether to run any of the optimization passes.
     *
     * <p>The default is {@code true}.
     */
    public Builder optimize(boolean optimize) {
      this.optimize = optimize;
      return this;
    }

    public Builder addHtmlAttributesForDebugging(boolean addHtmlAttributesForDebugging) {
      this.addHtmlAttributesForDebugging = addHtmlAttributesForDebugging;
      return this;
    }

    /** Configures this passmanager to run the conformance pass using the given config object. */
    public Builder setConformanceConfig(ValidatedConformanceConfig conformanceConfig) {
      this.conformanceConfig = checkNotNull(conformanceConfig);
      return this;
    }

    public Builder setLoggingConfig(ValidatedLoggingConfig loggingConfig) {
      this.loggingConfig = checkNotNull(loggingConfig);
      return this;
    }

    /**
     * Can be used to enable/disable the autoescaper.
     *
     * <p>The autoescaper is enabled by default.
     */
    public Builder setAutoescaperEnabled(boolean autoescaperEnabled) {
      this.autoescaperEnabled = autoescaperEnabled;
      return this;
    }

    public PassManager build() {
      return conformanceOnly ? new PassManager(this, /*unused=*/ true) : new PassManager(this);
    }
  }
}
