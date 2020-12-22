package br.unb.cic.analysis.ioa;

import br.unb.cic.analysis.AbstractAnalysis;
import br.unb.cic.analysis.AbstractMergeConflictDefinition;
import br.unb.cic.analysis.df.DataFlowAbstraction;
import br.unb.cic.analysis.model.Conflict;
import br.unb.cic.analysis.model.Statement;
import soot.*;
import soot.jimple.AssignStmt;
import soot.jimple.InstanceFieldRef;
import soot.jimple.InvokeStmt;
import soot.jimple.StaticFieldRef;
import soot.toolkits.scalar.ArraySparseSet;
import soot.toolkits.scalar.FlowSet;

import java.util.*;
import java.util.stream.Collectors;

public class InterproceduralOverrideAssignment extends SceneTransformer implements AbstractAnalysis {

    private Set<Conflict> conflicts;
    private Set<SootMethod> visitedMethods;
    private PointsToAnalysis pta;
    private AbstractMergeConflictDefinition definition;

    // TODO dataflowabstraction provavelmente deve ser subustituido por algo que faça mais sentido na analise inter procedural
    // TODO Adicionar tratamento de if, loops... (ForwardFlowAnalysis)
    private FlowSet<DataFlowAbstraction> res;
    private Body body;

    public InterproceduralOverrideAssignment(AbstractMergeConflictDefinition definition) {

        this.visitedMethods = new HashSet<>();
        this.conflicts = new HashSet<>();

        this.definition = definition;
        this.res = new ArraySparseSet<>();
    }

    @Override
    public void clear() {
        conflicts.clear();
    }

    @Override
    public Set<Conflict> getConflicts() {
        return conflicts;
    }

    private void configureEntryPoints() {
        List<SootMethod> entryPoints = new ArrayList<>();
        definition.getSourceStatements().forEach(s -> {
            entryPoints.add(s.getSootMethod());
        });
        Scene.v().setEntryPoints(entryPoints);
    }

    @Override
    protected void internalTransform(String s, Map<String, String> map) {
        definition.loadSourceStatements();
        definition.loadSinkStatements();
        List<SootMethod> traversedMethods = new ArrayList<>();

        configureEntryPoints();

        List<SootMethod> methods = Scene.v().getEntryPoints();
        pta = Scene.v().getPointsToAnalysis();
        methods.forEach(m -> traverse(m, traversedMethods, Statement.Type.IN_BETWEEN));
    }

    private void traverse(SootMethod sm, List<SootMethod> traversed, Statement.Type changeTag) {

        if (visitedMethods.contains(sm) || sm.isPhantom()) {
            return;
        }
        traversed.add(sm);
        this.body = sm.retrieveActiveBody();

        body.getUnits().forEach(unit -> {

            detectConflict(res, unit, changeTag, sm);

            if (isTagged(changeTag, unit)) {
                // TODO  mover if e else para metodos diferentes
                if (unit instanceof AssignStmt) {
                    // TODO Verificar AssignStmt contem objetos, arrays ou outros tipos?
                    AssignStmt assignStmt = (AssignStmt) unit;

                    // TODO Verificar caso: x = foo() + foo()
                    if (assignStmt.containsInvokeExpr()) {
                        traverse(assignStmt.getInvokeExpr().getMethod(), traversed, changeTag);
                    }

                    // TODO renomear Statement. (UnitWithExtraInformations)
                    Statement stmt = getStatementAssociatedWithUnit(sm, unit, changeTag);
                    gen(stmt);

                    // TODO Verificar tratamento em caso de for
                } else if (unit instanceof InvokeStmt) {
                    InvokeStmt invokeStmt = (InvokeStmt) unit;
                    Statement stmt = getStatementAssociatedWithUnit(sm, unit, changeTag);
                    // TODO trocar stmt.getType() por changeTag
                    traverse(invokeStmt.getInvokeExpr().getMethod(), traversed, stmt.getType());
                }
            } else {
                // TODO parametrizar
                if (unit instanceof AssignStmt) {
                    AssignStmt assignStmt = (AssignStmt) unit;
                    Statement stmt = getStatementAssociatedWithUnit(sm, unit, changeTag);

                    if (assignStmt.containsInvokeExpr()) {
                        traverse(assignStmt.getInvokeExpr().getMethod(), traversed, stmt.getType());
                    }

                    kill(unit);

                } else if (unit instanceof InvokeStmt) {
                    InvokeStmt invokeStmt = (InvokeStmt) unit;
                    Statement stmt = getStatementAssociatedWithUnit(sm, unit, changeTag);
                    traverse(invokeStmt.getInvokeExpr().getMethod(), traversed, stmt.getType());
                }
            }
        });
    }

    private boolean isTagged(Statement.Type changeTag, Unit unit) {
        return (isLeftStatement(unit) || isRightStatement(unit)) || (isInLeftStatementFLow(changeTag) || isInRightStatementFLow(changeTag));
    }

    private boolean isInRightStatementFLow(Statement.Type changeTag) {
        return changeTag.equals(Statement.Type.SINK);
    }

    private boolean isInLeftStatementFLow(Statement.Type changeTag) {
        return changeTag.equals(Statement.Type.SOURCE);
    }

    // TODO precisa tratar outros casos
    // TODO adicionar em duas litas (left e right).
    // TODO adicionar profundidade InstanceFieldRef e StaticFieldRef
    private void gen(Statement stmt) {
        stmt.getUnit().getDefBoxes().forEach(valueBox -> {
            if (valueBox.getValue() instanceof Local) {
                res.add(new DataFlowAbstraction((Local) valueBox.getValue(), stmt));
            } else if (valueBox.getValue() instanceof StaticFieldRef) {
                res.add(new DataFlowAbstraction((StaticFieldRef) valueBox.getValue(), stmt));
            } else if (valueBox.getValue() instanceof InstanceFieldRef) {
                // TODO verificar o que é adicionado. (Objeto.field)
                res.add(new DataFlowAbstraction((InstanceFieldRef) valueBox.getValue(), stmt));
            }
        });
    }

    private void kill(Unit unit) {
        for (DataFlowAbstraction dataFlowAbstraction : res) {
            // TODO extrair metodo res.removeAll(unit.getDefBoxes())
            for (ValueBox valueBox : unit.getDefBoxes()) {
                if (compareItens(valueBox, dataFlowAbstraction)) {
                    res.remove(dataFlowAbstraction);
                }
            }
        }
    }

    // TODO Obter res via atributo
    /*
     * To detect conflicts in verified if "u" is owned by LEFT or RIGHT
     * and we fill in the "potentialConflictingAssignments" list with the changes from the other developer.
     *
     * We pass "u" and "potentialConflictingAssignments" to the checkConflits method
     * to see if Left assignments interfere with Right changes or
     * Right assignments interfere with Left changes.
     */
    private void detectConflict(FlowSet<DataFlowAbstraction> in, Unit u, Statement.Type changeTag, SootMethod sm) {

        if (!isTagged(changeTag, u)) {
            return;
        }

        List<DataFlowAbstraction> potentialConflictingAssignments = new ArrayList<>();

        if (isRightStatement(u) || isInRightStatementFLow(changeTag)) {
            potentialConflictingAssignments = in.toList().stream().filter(
                    DataFlowAbstraction::containsLeftStatement).collect(Collectors.toList());
        } else if (isLeftStatement(u) || isInLeftStatementFLow(changeTag)) {
            potentialConflictingAssignments = in.toList().stream().filter(
                    DataFlowAbstraction::containsRightStatement).collect(Collectors.toList());
        }

        checkConflicts(u, potentialConflictingAssignments, changeTag, sm);

    }

    /*
     * Checks if there is a conflict and if so adds it to the conflict list.
     */
    private void checkConflicts(Unit unit, List<DataFlowAbstraction> potentialConflictingAssignments, Statement.Type changeTag, SootMethod sm) {
        for (DataFlowAbstraction dataFlowAbstraction : potentialConflictingAssignments) {
            for (ValueBox valueBox : unit.getDefBoxes()) {
                if (compareItens(valueBox, dataFlowAbstraction)) {
                    Conflict c = new Conflict(getStatementAssociatedWithUnit(sm, unit, changeTag), dataFlowAbstraction.getStmt());
                    conflicts.add(c);
                    System.out.println(c);
                }
            }
        }
    }

    // TODO melhorar nome do metodo
    // TODO não comparar como string
    private boolean compareItens(ValueBox valueBox, DataFlowAbstraction dataFlowAbstraction) {
        if (valueBox.getValue() instanceof InstanceFieldRef && dataFlowAbstraction.getFieldRef() != null) {
            return getVariableNameInFromValueBoxInstanceFieldRef(valueBox).equals(dataFlowAbstraction.getFieldRef().getBase().toString());
        } else if (valueBox.getValue() instanceof StaticFieldRef && dataFlowAbstraction.getLocalStaticRef() != null) {
            return getVariableNameInFromValueBoxStaticFieldRef(valueBox).equals(dataFlowAbstraction.getLocalStaticRef().getField().getName());
        } else if (valueBox.getValue() instanceof Local && dataFlowAbstraction.getLocal() != null) {
            return getVariableNameInFromValueBoxLocal(valueBox).equals(dataFlowAbstraction.getLocal().getName());
        }
        return false;
    }

    private String getVariableNameInFromValueBoxInstanceFieldRef(ValueBox valueBox) {
        InstanceFieldRef instanceFieldRef = (InstanceFieldRef) valueBox.getValue();
        return instanceFieldRef.getBase().toString();
    }

    private String getVariableNameInFromValueBoxStaticFieldRef(ValueBox valueBox) {
        StaticFieldRef staticFieldRef = (StaticFieldRef) valueBox.getValue();
        return staticFieldRef.getField().getName();
    }

    private String getVariableNameInFromValueBoxLocal(ValueBox valueBox) {
        Local local = (Local) valueBox.getValue();
        return local.getName();
    }

    /*
     * Returns the Statement changeTag
     */
    private Statement getStatementAssociatedWithUnit(SootMethod sm, Unit u, Statement.Type changeTag) {
        if (isLeftStatement(u)) {
            return findLeftStatement(u);
        } else if (isRightStatement(u)) {
            return findRightStatement(u);
        } else if (!isLeftStatement(u) && isInLeftStatementFLow(changeTag)) {
            return createStatement(sm, u, changeTag);
        } else if (!isRightStatement(u) && isInRightStatementFLow(changeTag)) {
            return createStatement(sm, u, changeTag);
        }
        return findStatementBase(u);
    }

    private boolean isLeftStatement(Unit u) {
        return definition.getSourceStatements().stream().map(Statement::getUnit).collect(Collectors.toList()).contains(u);
    }

    private boolean isRightStatement(Unit u) {
        return definition.getSinkStatements().stream().map(Statement::getUnit).collect(Collectors.toList()).contains(u);
    }

    private Statement findRightStatement(Unit u) {
        return definition.getSinkStatements().stream().filter(s -> s.getUnit().equals(u)).
                findFirst().get();
    }

    private Statement findLeftStatement(Unit u) {
        return definition.getSourceStatements().stream().filter(s -> s.getUnit().equals(u)).
                findFirst().get();
    }

    private Statement findStatementBase(Unit d) {
        return Statement.builder()
                .setClass(body.getMethod().getDeclaringClass())
                .setMethod(body.getMethod())
                .setType(Statement.Type.IN_BETWEEN)
                .setUnit(d)
                .setSourceCodeLineNumber(d.getJavaSourceStartLineNumber()).build();
    }

    private Statement createStatement(SootMethod sm, Unit u, Statement.Type changeTag) {
        return Statement.builder().setClass(sm.getDeclaringClass()).setMethod(sm)
                .setUnit(u).setType(changeTag).setSourceCodeLineNumber(u.getJavaSourceStartLineNumber())
                .build();
    }

    /*private String getVariableNameInFromValueBox(ValueBox valueBox) {
        if (valueBox.getValue() instanceof InstanceFieldRef) {
            InstanceFieldRef instanceFieldRef = (InstanceFieldRef) valueBox.getValue();
            return instanceFieldRef.getBase().toString();
        } else if (valueBox.getValue() instanceof Local) {
            Local local = (Local) valueBox.getValue();
            return local.getName();
        } else if (valueBox.getValue() instanceof StaticFieldRef) {
            StaticFieldRef staticFieldRef = (StaticFieldRef) valueBox.getValue();
            return staticFieldRef.getField().getName();
        } else {
            return "";
        }
    }*/
    /*    private String getVariableNameInDataFlowAbstraction(DataFlowAbstraction dataFlowAbstraction) {
        if (dataFlowAbstraction.getLocal() != null) {
            return dataFlowAbstraction.getLocal().getName();
        } else if (dataFlowAbstraction.getLocalStaticRef() != null) {
            return dataFlowAbstraction.getLocalStaticRef().getField().getName();
        } else {
            return dataFlowAbstraction.getFieldRef().getBase().toString();
        }

    }*/
}
