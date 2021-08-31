package br.unb.cic.analysis.df.pessimistic;

import br.unb.cic.analysis.AbstractAnalysis;
import br.unb.cic.analysis.AbstractMergeConflictDefinition;
import br.unb.cic.analysis.model.Conflict;
import br.unb.cic.analysis.model.Statement;
import soot.Body;
import soot.Unit;
import soot.ValueBox;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.scalar.ForwardFlowAnalysis;

import java.util.HashSet;
import java.util.Set;

public class PessimisticTaintedAnalysis extends ForwardFlowAnalysis<Unit, PessimisticTaintedAnalysisAbstraction> implements AbstractAnalysis {

    private Body methodBody;
    private Set<Conflict> conflicts;
    private AbstractMergeConflictDefinition definition;

    public PessimisticTaintedAnalysis(Body methodBody, AbstractMergeConflictDefinition definition) {
        super(new ExceptionalUnitGraph(methodBody));
        this.methodBody = methodBody;
        this.conflicts = new HashSet<>();
        this.definition = definition;
        definition.loadSinkStatements();
        definition.loadSourceStatements();
        doAnalysis();
    }

    @Override
    protected PessimisticTaintedAnalysisAbstraction newInitialFlow() {
        return new PessimisticTaintedAnalysisAbstraction();
    }

    @Override
    protected void flowThrough(PessimisticTaintedAnalysisAbstraction in, Unit unit, PessimisticTaintedAnalysisAbstraction out) {
        Statement statement = createStatement(unit);
        in.copy(out);
        detectConflicts(in, statement);
        out.difference(kill(statement));
        out.union(gen(statement));
    }

    protected Statement createStatement(Unit d) {
        Statement.Type type = Statement.Type.IN_BETWEEN;

        // TODO: For now we will assume that a field can only be either source or sink
        if (this.definition.isSourceStatement(d)) {
            type = Statement.Type.SOURCE;
        } else if (this.definition.isSinkStatement(d)){
            type = Statement.Type.SINK;
        }

        return Statement.builder()
                .setClass(methodBody.getMethod().getDeclaringClass())
                .setMethod(methodBody.getMethod())
                .setType(type)
                .setUnit(d)
                .setSourceCodeLineNumber(d.getJavaSourceStartLineNumber()).build();
    }

    protected void detectConflicts(PessimisticTaintedAnalysisAbstraction in, Statement statement) {
        for (ValueBox use : statement.getUnit().getUseBoxes()) {
            if (in.isMarked(use.getValue())) {
                // add conflict
            }
        }
        if (statement.isInvoke()) {

            // add conflict
        }
    }

    protected PessimisticTaintedAnalysisAbstraction gen(Statement statement) {
        PessimisticTaintedAnalysisAbstraction res = new PessimisticTaintedAnalysisAbstraction();

        if (statement.getType() == Statement.Type.SOURCE) {
            if (statement.isAssign()) {
                // mark target
            }
            if (statement.isInvoke()) {
                // mark called object instance fields
            }
        }

        return res;
    }

    protected PessimisticTaintedAnalysisAbstraction kill(Statement statement) {
        PessimisticTaintedAnalysisAbstraction res = new PessimisticTaintedAnalysisAbstraction();

        if (statement.isAssign()) {
            // mark target
        }
        if (statement.isInvoke()) {
            // mark called object instance fields
        }

        return res;
    }

    @Override
    protected void merge(PessimisticTaintedAnalysisAbstraction in1, PessimisticTaintedAnalysisAbstraction in2, PessimisticTaintedAnalysisAbstraction out) {
        in1.union(in2, out);
    }

    @Override
    protected void copy(PessimisticTaintedAnalysisAbstraction in, PessimisticTaintedAnalysisAbstraction out) {
        in.copy(out);
    }

    @Override
    public void clear() {
        conflicts.clear();
    }

    @Override
    public Set<Conflict> getConflicts() {
        return conflicts;
    }
}
