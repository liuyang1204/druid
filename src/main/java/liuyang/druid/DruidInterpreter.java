package liuyang.druid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

import liuyang.druid.DruidParser.ArrayCallExprContext;
import liuyang.druid.DruidParser.ArrayContext;
import liuyang.druid.DruidParser.AssignContext;
import liuyang.druid.DruidParser.DefineContext;
import liuyang.druid.DruidParser.ExprContext;
import liuyang.druid.DruidParser.ExtendContext;
import liuyang.druid.DruidParser.FunctionCallContext;
import liuyang.druid.DruidParser.FunctionContext;
import liuyang.druid.DruidParser.HashContext;
import liuyang.druid.DruidParser.NegExprContext;
import liuyang.druid.DruidParser.OpExprContext;
import liuyang.druid.DruidParser.ParenExprContext;
import liuyang.druid.DruidParser.ReturnstContext;
import liuyang.druid.DruidParser.SignalContext;
import liuyang.druid.DruidParser.StatementContext;
import liuyang.druid.Scope.DependencyEdge;
import liuyang.druid.signal.FileSignal;
import liuyang.druid.signal.Signal;
import liuyang.druid.signal.SignalReceiver;

import org.antlr.v4.runtime.tree.TerminalNode;
import org.jgrapht.DirectedGraph;
import org.jgrapht.alg.CycleDetector;

public class DruidInterpreter extends DruidBaseVisitor<Object> implements
        SignalReceiver {

    private Map<String, FunctionContext> functions;

    private Map<SignalContext, FileSignal> fileSignalContexts = new HashMap<>();

    public DruidInterpreter(Map<String, FunctionContext> functions) {
        this.functions = functions;
        scopeStack.push(new Scope());
    }

    private Stack<Scope> scopeStack = new Stack<>();

    public Map<String, Object> getValues() {
        return scopeStack.peek().getValues();
    }

    public Map<SignalContext, FileSignal> getFileSignalContexts() {
        return fileSignalContexts;
    }

    @Override
    public Object visitDefine(DefineContext ctx) {
        for (TerminalNode node : ctx.ID()) {
            String name = node.getText();
            if (scopeStack.peek().contains(name)) {
                throw new IllegalStateException("variable " + name
                        + " already defined!");
            } else {
                scopeStack.peek().addVariable(name);
            }
        }
        return null;
    }

    @Override
    public Object visitFunction(FunctionContext ctx) {
        return null;
    }

    @Override
    public Object visitAssign(AssignContext ctx) {
        String name = ctx.ID().getText();
        clearDependency(name);
        if (!scopeStack.peek().contains(name)) {
            throw new IllegalStateException("variable " + name
                    + " not defined!");
        } else {
            Object originalValue = scopeStack.peek().getValue(name);
            Object value = visit(ctx.expr());
            if (!value.equals(originalValue)) {
                scopeStack.peek().setValue(name, value);
                trigger(name);
            }
        }
        return null;
    }

    private enum DataType {
        INTEGER, STRING, HASH, ARRAY
    }

    private DataType judgeDataType(Object object) {
        if (object instanceof Integer) {
            return DataType.INTEGER;
        } else if (object instanceof List) {
            return DataType.ARRAY;
        } else if (object instanceof Map) {
            return DataType.HASH;
        } else if (object instanceof String) {
            return DataType.STRING;
        } else {
            throw new IllegalStateException("unexpected!");
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object visitArrayCallExpr(ArrayCallExprContext ctx) {
        Object value = visit(ctx.arr);
        DataType dataType = judgeDataType(value);
        if (dataType != DataType.ARRAY) {
            throw new IllegalStateException("can not apply array operation on "
                    + dataType);
        } else {
            List<Object> array = (List<Object>) value;
            Object index = visit(ctx.index);
            DataType indexDataType = judgeDataType(index);
            if (indexDataType != DataType.INTEGER) {
                throw new IllegalStateException("array index should be integer");
            }
            return array.get((Integer) index);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object visitOpExpr(OpExprContext ctx) {
        String op = ctx.op.getText();
        Object leftValue = visit(ctx.left);
        Object rightValue = visit(ctx.right);
        DataType leftType = judgeDataType(leftValue);
        DataType rightType = judgeDataType(rightValue);
        if (leftType != rightType) {
            throw new IllegalStateException("undefined operator '" + op
                    + "' between " + leftType + " and " + rightType + "!");
        } else {
            if (leftType == DataType.INTEGER) {
                Integer leftInteger = (Integer) leftValue;
                Integer rightInteger = (Integer) rightValue;
                switch (op) {
                case "+":
                    return leftInteger + rightInteger;
                case "-":
                    return leftInteger - rightInteger;
                case "*":
                    return leftInteger * rightInteger;
                case "/":
                    return leftInteger / rightInteger;
                default:
                    throw new IllegalStateException("undefined operator '" + op
                            + "' between " + leftType + " and " + rightType
                            + "!");
                }
            } else if (leftType == DataType.ARRAY) {
                List<Object> leftList = (List<Object>) leftValue;
                List<Object> rightList = (List<Object>) rightValue;
                List<Object> result = new ArrayList<>(leftList);
                switch (op) {
                case "+":
                    result.addAll(rightList);
                    return result;
                case "-":
                    result.removeAll(rightList);
                    return result;
                default:
                    throw new IllegalStateException("undefined operator '" + op
                            + "' between " + leftType + " and " + rightType
                            + "!");
                }
            } else if (leftType == DataType.STRING) {
                String leftStr = (String) leftValue;
                String rightStr = (String) rightValue;
                switch (op) {
                case "+":
                    return leftStr.concat(rightStr);
                default:
                    throw new IllegalStateException("undefined operator '" + op
                            + "' between " + leftType + " and " + rightType
                            + "!");
                }
            } else {
                throw new IllegalStateException("unexpected!");
            }
        }

    }

    @Override
    public Object visitParenExpr(ParenExprContext ctx) {
        return visit(ctx.expr());
    }

    @Override
    public Object visitNegExpr(NegExprContext ctx) {
        return -(Integer) visit(ctx.expr());
    }

    @Override
    public Object visitFunctionCall(FunctionCallContext ctx) {
        String name = ctx.ID().getText();
        List<Object> argValues = ctx.args.stream().map(arg -> visit(arg))
                .collect(Collectors.toList());
        if (functions.containsKey(name)) {
            FunctionContext function = functions.get(name);
            return valFunction(function, argValues);

        } else if (Function.BUILT_IN_FUNCTIONS.containsKey(name)) {
            return Function.BUILT_IN_FUNCTIONS.get(name).run(
                    argValues.toArray());
        } else {
            throw new IllegalStateException("function " + name
                    + " is not defined!");
        }

    }

    @Override
    public Object visitSignal(SignalContext ctx) {
        String signalName = ctx.SID().getText().substring(1);
        switch (signalName) {
        case "file":
            String fileName = (String) visit(ctx.args.get(0));
            FileSignal fileSignal = new FileSignal(fileName);
            fileSignalContexts.put(ctx, fileSignal);
            referredVariables.add(fileSignal.identity());
            return fileSignal.value();
        default:
            throw new IllegalStateException("undefined signal type "
                    + signalName + "!");
        }
    }

    @Override
    public Object visitArray(ArrayContext ctx) {
        return ctx.elements.stream().map(element -> visit(element))
                .collect(Collectors.toList());
    }

    @Override
    public Object visitHash(HashContext ctx) {
        // TODO Auto-generated method stub
        return super.visitHash(ctx);
    }

    @Override
    public Object visitTerminal(TerminalNode node) {
        String tokenName = DruidLexer.tokenNames[node.getSymbol().getType()];
        switch (tokenName) {
        case "INT":
            return new Integer(node.getText());
        case "ID":
            String name = node.getText();
            if (!scopeStack.peek().contains(name)) {
                throw new IllegalStateException("variable " + name
                        + " not defined!");
            } else {
                Object result = scopeStack.peek().getValue(name);
                if (result == null) {
                    throw new IllegalStateException("variable " + name
                            + " is not initialized!");
                } else {
                    referredVariables.add(name);
                    return result;
                }
            }
        case "STRING":
            String str = node.getText().substring(1,
                    node.getText().length() - 1);
            return str;
        default:
            return null;
        }
    }

    private void trigger(String name) {
        Scope scope = scopeStack.peek();
        DirectedGraph<String, DependencyEdge> dependencyGraph = scope
                .getDependencyGraph();
        if (dependencyGraph.containsVertex(name)) {
            for (DependencyEdge edge : dependencyGraph.outgoingEdgesOf(name)) {
                String target = dependencyGraph.getEdgeTarget(edge);
                Object originalValue = scope.getValue(target);
                Object value = visit(edge.exprContext);
                if (!value.equals(originalValue)) {
                    scope.setValue(target, value);
                    trigger(target);
                }
            }
        }
    }

    private Set<String> referredVariables = new HashSet<>();

    private Object valFunction(FunctionContext function, List<Object> argValues) {
        Scope scope = new Scope();
        if (function.params.size() != argValues.size()) {
            throw new IllegalStateException("function '"
                    + function.ID.getText()
                    + "' params number not matched, expected "
                    + function.params.size() + " but was " + argValues.size()
                    + "!");
        }
        for (int i = 0; i < function.params.size(); i++) {
            scope.setValue(function.params.get(i).getText(), argValues.get(i));
        }
        scopeStack.push(scope);
        for (StatementContext statement : function.statement()) {
            if (statement.returnst() != null) {
                Object result = visit(statement);
                scopeStack.pop();
                return result;
            } else {
                visit(statement);
            }
        }
        throw new IllegalStateException("No return statement in function "
                + function.name.getText());
    }

    @Override
    public Object visitReturnst(ReturnstContext ctx) {
        return visit(ctx.expr());
    }

    private void clearDependency(String name) {
        Scope scope = scopeStack.peek();
        DirectedGraph<String, DependencyEdge> dependencyGraph = scope
                .getDependencyGraph();
        if (dependencyGraph.containsVertex(name)) {
            for (DependencyEdge edge : dependencyGraph.incomingEdgesOf(name)) {
                dependencyGraph.removeEdge(edge);
            }
            if (dependencyGraph.edgesOf(name).isEmpty()) {
                dependencyGraph.removeVertex(name);
            }
        }
    }

    @Override
    public Object visitExtend(ExtendContext ctx) {
        Scope scope = scopeStack.peek();
        DirectedGraph<String, DependencyEdge> dependencyGraph = scope
                .getDependencyGraph();
        String name = ctx.ID().getText();
        clearDependency(name);
        ExprContext expr = ctx.expr();
        referredVariables = new HashSet<>();
        Object result = visit(expr);
        if (!referredVariables.isEmpty()) {
            dependencyGraph.addVertex(name);
            for (String from : referredVariables) {
                dependencyGraph.addVertex(from);
                dependencyGraph.addEdge(from, name, new DependencyEdge(expr));
            }
            CycleDetector<String, DependencyEdge> cycleDetector = new CycleDetector<>(
                    dependencyGraph);
            if (cycleDetector.detectCycles()) {
                throw new IllegalStateException("cycle dependency found!");
            }
        }
        scope.setValue(name, result);
        return null;
    }

    @Override
    public void receive(Signal signal) {
        trigger(signal.identity());
    }

}
