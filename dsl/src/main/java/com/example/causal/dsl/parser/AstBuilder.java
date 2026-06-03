package com.example.causal.dsl.parser;

import com.example.causal.dsl.ast.*;
import com.example.causal.dsl.model.VariableRole;
import com.example.causal.dsl.model.VariableType;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Converts an ANTLR parse tree into a list of DSL AST statements.
 */
public class AstBuilder extends CausalDSLBaseVisitor<Object> {

    /**
     * Parse {@code input} and return the ordered list of statements.
     * Throws {@link ParseException} on any syntax error.
     */
    @SuppressWarnings("unchecked")
    public List<Statement> buildStatements(String input) {
        var lexer  = new CausalDSLLexer(CharStreams.fromString(input));
        var tokens = new CommonTokenStream(lexer);
        var parser = new CausalDSLParser(tokens);

        // Replace the default console error listeners with one that throws.
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        var errorListener = new ThrowingErrorListener();
        lexer.addErrorListener(errorListener);
        parser.addErrorListener(errorListener);

        return (List<Statement>) visit(parser.program());
    }

    // ─── Program ──────────────────────────────────────────────────────────────

    @Override
    public Object visitProgram(CausalDSLParser.ProgramContext ctx) {
        List<Statement> stmts = new ArrayList<>();
        for (CausalDSLParser.StatementContext sc : ctx.statement()) {
            stmts.add((Statement) visit(sc));
        }
        return stmts;
    }

    @Override
    public Object visitStatement(CausalDSLParser.StatementContext ctx) {
        return visit(ctx.getChild(0));
    }

    // ─── Definitions ──────────────────────────────────────────────────────────

    @Override
    public Object visitDefineVariableStmt(CausalDSLParser.DefineVariableStmtContext ctx) {
        return new DefineVariableStmt(
            ctx.name.getText(),
            ctx.unit != null ? ctx.unit.getText() : null,
            ctx.observedAs != null ? new ObservationBinding(stripQuotes(ctx.observedAs.getText()), ctx.unit != null ? ctx.unit.getText() : null) : null,
            ctx.varType != null ? toVariableType(ctx.varType) : null,
            ctx.varRole != null ? toVariableRole(ctx.varRole) : null
        );
    }

    @Override
    public Object visitDefineModelBlock(CausalDSLParser.DefineModelBlockContext ctx) {
        List<VariableDecl> vars = ctx.variableDecl().stream()
            .map(vd -> (VariableDecl) visit(vd))
            .collect(Collectors.toList());
        List<DagEdge> edges = ctx.dagBlock().edge().stream()
            .map(e -> new DagEdge(e.from.getText(), e.to.getText(), dbl(e.w)))
            .collect(Collectors.toList());
        return new DefineModelStmt(ctx.name.getText(), vars, edges);
    }

    @Override
    public Object visitDefineCausalDagBlock(CausalDSLParser.DefineCausalDagBlockContext ctx) {
        List<DagEdge> edges = ctx.edge().stream()
            .map(e -> new DagEdge(e.from.getText(), e.to.getText(), dbl(e.w)))
            .collect(Collectors.toList());
        return new DefineDagStmt(ctx.name.getText(), edges);
    }

    @Override
    public Object visitVariableDecl(CausalDSLParser.VariableDeclContext ctx) {
        return new VariableDecl(
            ctx.name.getText(),
            ctx.unit != null ? ctx.unit.getText() : null,
            ctx.observedAs != null ? new ObservationBinding(stripQuotes(ctx.observedAs.getText()), ctx.unit != null ? ctx.unit.getText() : null) : null,
            ctx.varType != null ? toVariableType(ctx.varType) : null,
            ctx.varRole != null ? toVariableRole(ctx.varRole) : null,
            dbl(ctx.m),
            dbl(ctx.n)
        );
    }

    // ─── Structural queries ───────────────────────────────────────────────────

    @Override public Object visitCausesOfStmt(CausalDSLParser.CausesOfStmtContext ctx) {
        return new CausesOfStmt(ctx.var.getText());
    }

    @Override public Object visitEffectsOfStmt(CausalDSLParser.EffectsOfStmtContext ctx) {
        return new EffectsOfStmt(ctx.var.getText());
    }

    @Override public Object visitAncestorsOfStmt(CausalDSLParser.AncestorsOfStmtContext ctx) {
        return new AncestorsOfStmt(ctx.var.getText());
    }

    @Override public Object visitDescendantsOfStmt(CausalDSLParser.DescendantsOfStmtContext ctx) {
        return new DescendantsOfStmt(ctx.var.getText());
    }

    @Override public Object visitMediatorsBetweenStmt(CausalDSLParser.MediatorsBetweenStmtContext ctx) {
        return new MediatorsBetweenStmt(ctx.from.getText(), ctx.to.getText());
    }

    @Override public Object visitCollidersBetweenStmt(CausalDSLParser.CollidersBetweenStmtContext ctx) {
        return new CollidersBetweenStmt(ctx.from.getText(), ctx.to.getText());
    }

    @Override public Object visitConfoundersOfStmt(CausalDSLParser.ConfoundersOfStmtContext ctx) {
        return new ConfoundersOfStmt(ctx.from.getText(), ctx.to.getText());
    }

    @Override public Object visitBackdoorPathsStmt(CausalDSLParser.BackdoorPathsStmtContext ctx) {
        return new BackdoorPathsStmt(ctx.from.getText(), ctx.to.getText());
    }

    // ─── Identification & estimation ─────────────────────────────────────────

    @Override
    public Object visitIdentifyStmt(CausalDSLParser.IdentifyStmtContext ctx) {
        return new IdentifyStmt(
            ctx.treatment.getText(),
            ctx.outcome.getText(),
            ctx.ADJUSTMENT() != null,
            ctx.EXPLAIN() != null
        );
    }

    @Override
    public Object visitEstimateStmt(CausalDSLParser.EstimateStmtContext ctx) {
        List<String> adjustFor = ctx.varList() != null
            ? ctx.varList().ID().stream().map(ParseTree::getText).collect(Collectors.toList())
            : List.of();
        return new EstimateStmt(
            ctx.treatment.getText(),
            ctx.outcome.getText(),
            adjustFor,
            ctx.estimator != null ? ctx.estimator.getText() : null
        );
    }

    // ─── Intervention ─────────────────────────────────────────────────────────

    @Override
    public Object visitInterveneStmt(CausalDSLParser.InterveneStmtContext ctx) {
        Assignment compareTo = ctx.compareTo != null ? toAssignment(ctx.compareTo) : null;
        String[] ret = resolveInterveneReturn(ctx.interveneReturn());
        return new InterveneStmt(
            ctx.modelRef != null ? ctx.modelRef.getText() : null,
            toAssignment(ctx.intervention),
            compareTo,
            ret[0],
            ret[1]
        );
    }

    private String[] resolveInterveneReturn(CausalDSLParser.InterveneReturnContext ctx) {
        if (ctx instanceof CausalDSLParser.DistributionReturnContext d)
            return new String[]{"DISTRIBUTION", d.var.getText()};
        if (ctx instanceof CausalDSLParser.EffectReturnContext e)
            return new String[]{"EFFECT", e.var.getText()};
        if (ctx instanceof CausalDSLParser.ExpectationReturnContext ex)
            return new String[]{"EXPECTATION", ex.var.getText()};
        throw new ParseException("Unknown RETURN type in INTERVENE statement");
    }

    // ─── Counterfactual ───────────────────────────────────────────────────────

    @Override
    public Object visitCounterfactualStmt(CausalDSLParser.CounterfactualStmtContext ctx) {
        List<Assignment> given = ctx.conditionList() != null
            ? ctx.conditionList().assignment().stream().map(this::toAssignment).collect(Collectors.toList())
            : List.of();
        return new CounterfactualStmt(
            ctx.unit != null ? ctx.unit.getText() : null,
            ctx.unitId != null ? stripQuotes(ctx.unitId.getText()) : null,
            given,
            ctx.outcome.getText(),
            toAssignment(ctx.ifAssignment)
        );
    }

    // ─── Context switching ─────────────────────────────────────────────────────

    @Override public Object visitUseModelStmt(CausalDSLParser.UseModelStmtContext ctx) {
        return new UseModelStmt(ctx.name.getText());
    }

    @Override public Object visitResetStmt(CausalDSLParser.ResetStmtContext ctx) {
        return new ResetStmt();
    }

    @Override public Object visitShowModelsStmt(CausalDSLParser.ShowModelsStmtContext ctx) {
        return new ShowModelsStmt();
    }

    // ─── Inspection ───────────────────────────────────────────────────────────

    @Override public Object visitShowDagStmt(CausalDSLParser.ShowDagStmtContext ctx) {
        return new ShowDagStmt(ctx.name != null ? ctx.name.getText() : null);
    }

    @Override public Object visitDescribeVariableStmt(CausalDSLParser.DescribeVariableStmtContext ctx) {
        return new DescribeVariableStmt(ctx.name.getText());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Assignment toAssignment(CausalDSLParser.AssignmentContext ctx) {
        return new Assignment(ctx.var.getText(), ctx.value.getText());
    }

    /** Parse an optional NUMBER token to a Double, or null when the token is absent. */
    private static Double dbl(Token t) {
        return t != null ? Double.parseDouble(t.getText()) : null;
    }

    private VariableType toVariableType(CausalDSLParser.VariableTypeContext ctx) {
        return switch (ctx.getText()) {
            case "binary"      -> VariableType.BINARY;
            case "continuous"  -> VariableType.CONTINUOUS;
            case "categorical" -> VariableType.CATEGORICAL;
            case "event"       -> VariableType.EVENT;
            case "latent"      -> VariableType.LATENT;
            default -> throw new ParseException("Unknown variable type: " + ctx.getText());
        };
    }

    private VariableRole toVariableRole(CausalDSLParser.VariableRoleContext ctx) {
        return switch (ctx.getText()) {
            case "treatment"  -> VariableRole.TREATMENT;
            case "outcome"    -> VariableRole.OUTCOME;
            case "covariate"  -> VariableRole.COVARIATE;
            case "mediator"   -> VariableRole.MEDIATOR;
            case "collider"   -> VariableRole.COLLIDER;
            case "confounder" -> VariableRole.CONFOUNDER;
            case "unknown"    -> VariableRole.UNKNOWN;
            default -> throw new ParseException("Unknown variable role: " + ctx.getText());
        };
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\""))
            return s.substring(1, s.length() - 1).replace("\\\"", "\"");
        return s;
    }

    // ─── Error listener ───────────────────────────────────────────────────────

    private static class ThrowingErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine,
                                String msg, RecognitionException e) {
            throw new ParseException("Syntax error at line " + line + ":" + charPositionInLine + " — " + msg);
        }
    }
}
