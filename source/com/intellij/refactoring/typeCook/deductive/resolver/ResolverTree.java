package com.intellij.refactoring.typeCook.deductive.resolver;

import com.intellij.openapi.util.Pair;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.PsiTypeVariable;
import com.intellij.refactoring.typeCook.deductive.PsiExtendedTypeVisitor;
import com.intellij.refactoring.typeCook.deductive.builder.Constraint;
import com.intellij.refactoring.typeCook.deductive.builder.Subtype;
import com.intellij.psi.Bottom;
import com.intellij.refactoring.typeCook.Util;
import com.intellij.refactoring.typeCook.Settings;
import com.intellij.util.graph.Graph;
import com.intellij.util.graph.DFSTBuilder;

import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * User: db
 * Date: Dec 27, 2004
 * Time: 4:57:07 PM
 * To change this template use File | Settings | File Templates.
 */
public class ResolverTree {
  private static final Logger LOG = Logger.getInstance("#com.intellij.refactoring.typeCook.deductive.resolver.ResolverTree");

  private ResolverTree[] mySons = new ResolverTree[0];
  private BindingFactory myBindingFactory;
  private Binding myCurrentBinding;
  private SolutionHolder mySolutions;
  private Project myProject;
  private HashMap<PsiTypeVariable, Integer> myBindingDegree; //How many times this type variable is bound in the system
  private Settings mySettings;
  private boolean myCookWildcards = false;

  private HashSet<Constraint> myConstraints;

  public ResolverTree(final com.intellij.refactoring.typeCook.deductive.builder.System system) {
    myBindingFactory = new BindingFactory(system);
    mySolutions = new SolutionHolder();
    myCurrentBinding = myBindingFactory.create();
    myConstraints = system.getConstraints();
    myProject = system.getProject();
    myBindingDegree = calculateDegree();
    mySettings = system.getSettings();

    reduceCyclicVariables();
  }

  private ResolverTree(final ResolverTree parent, final HashSet<Constraint> constraints, final Binding binding) {
    myBindingFactory = parent.myBindingFactory;
    myCurrentBinding = binding;
    mySolutions = parent.mySolutions;
    myConstraints = constraints;
    myProject = parent.myProject;
    myBindingDegree = calculateDegree();
    mySettings = parent.mySettings;
    myCookWildcards = parent.myCookWildcards;
  }

  private static class PsiTypeVarCollector extends PsiExtendedTypeVisitor {
    final HashSet<PsiTypeVariable> mySet = new HashSet<PsiTypeVariable>();

    public Object visitTypeVariable(final PsiTypeVariable var) {
      mySet.add(var);

      return null;
    }

    public HashSet<PsiTypeVariable> getSet(final PsiType type) {
      type.accept(this);
      return mySet;
    }
  }

  private boolean isBoundElseWhere(final PsiTypeVariable var) {
    final Integer deg = myBindingDegree.get(var);

    return deg == null || deg.intValue() > 1;
  }

  private boolean canBePruned(final Binding b) {
    if (mySettings.exhaustive()) return false;
    for (final Iterator<PsiTypeVariable> v = b.getBoundVariables().iterator(); v.hasNext();) {
      final PsiTypeVariable var = v.next();
      final PsiType type = b.apply(var);

      if (!(type instanceof PsiTypeVariable) && isBoundElseWhere(var)) {
        return false;
      }
    }

    return true;
  }

  private HashMap<PsiTypeVariable, Integer> calculateDegree() {
    final HashMap<PsiTypeVariable, Integer> result = new HashMap<PsiTypeVariable, Integer>();

    for (final Iterator<Constraint> c = myConstraints.iterator(); c.hasNext();) {
      final Constraint constr = c.next();

      final PsiTypeVarCollector collector = new PsiTypeVarCollector();

      setDegree(collector.getSet(constr.getRight()), result);
    }

    return result;
  }

  private void setDegree(final HashSet<PsiTypeVariable> set, Map<PsiTypeVariable, Integer> result) {
    for (final Iterator<PsiTypeVariable> v = set.iterator(); v.hasNext();) {
      final PsiTypeVariable var = v.next();
      final Integer deg = result.get(var);

      if (deg == null) {
        result.put(var, new Integer(1));
      }
      else {
        result.put(var, new Integer(deg.intValue() + 1));
      }
    }
  }

  private HashSet<Constraint> apply(final Binding b) {
    final HashSet<Constraint> result = new HashSet<Constraint>();

    for (Iterator<Constraint> c = myConstraints.iterator(); c.hasNext();) {
      final Constraint constr = c.next();
      result.add(constr.apply(b));
    }

    return result;
  }

  private HashSet<Constraint> apply(final Binding b, final HashSet<Constraint> additional) {
    final HashSet<Constraint> result = new HashSet<Constraint>();

    for (Iterator<Constraint> c = myConstraints.iterator(); c.hasNext();) {
      final Constraint constr = c.next();
      result.add(constr.apply(b));
    }

    for (Iterator<Constraint> c = additional.iterator(); c.hasNext();) {
      final Constraint constr = c.next();
      result.add(constr.apply(b));
    }

    return result;
  }

  private ResolverTree applyRule(final Binding b) {
    final Binding newBinding = myCurrentBinding.compose(b);

    return newBinding == null ? null : new ResolverTree(this, apply(b), newBinding);
  }

  private ResolverTree applyRule(final Binding b, final HashSet<Constraint> additional) {
    final Binding newBinding = myCurrentBinding.compose(b);

    return newBinding == null ? null : new ResolverTree(this, apply(b, additional), newBinding);
  }

  private void reduceCyclicVariables() {
    final HashSet<PsiTypeVariable> nodes = new HashSet<PsiTypeVariable>();
    final HashSet<Constraint> candidates = new HashSet<Constraint>();

    final HashMap<PsiTypeVariable, HashSet<PsiTypeVariable>> ins = new HashMap<PsiTypeVariable, HashSet<PsiTypeVariable>>();
    final HashMap<PsiTypeVariable, HashSet<PsiTypeVariable>> outs = new HashMap<PsiTypeVariable, HashSet<PsiTypeVariable>>();

    for (Iterator<Constraint> c = myConstraints.iterator(); c.hasNext();) {
      final Constraint constraint = c.next();

      final PsiType left = constraint.getLeft();
      final PsiType right = constraint.getRight();

      if (left instanceof PsiTypeVariable && right instanceof PsiTypeVariable) {
        final PsiTypeVariable leftVar = (PsiTypeVariable)left;
        final PsiTypeVariable rightVar = (PsiTypeVariable)right;

        candidates.add(constraint);

        nodes.add(leftVar);
        nodes.add(rightVar);

        final HashSet<PsiTypeVariable> in = ins.get(leftVar);
        final HashSet<PsiTypeVariable> out = outs.get(rightVar);

        if (in == null) {
          final HashSet<PsiTypeVariable> newIn = new HashSet<PsiTypeVariable>();

          newIn.add(rightVar);

          ins.put(leftVar, newIn);
        }
        else {
          in.add(rightVar);
        }

        if (out == null) {
          final HashSet<PsiTypeVariable> newOut = new HashSet<PsiTypeVariable>();

          newOut.add(leftVar);

          outs.put(rightVar, newOut);
        }
        else {
          out.add(leftVar);
        }
      }
    }

    final DFSTBuilder<PsiTypeVariable> dfstBuilder = new DFSTBuilder<PsiTypeVariable>(new Graph<PsiTypeVariable>() {
                                                                                        public Collection<PsiTypeVariable> getNodes() {
                                                                                          return nodes;
                                                                                        }

                                                                                        public Iterator<PsiTypeVariable> getIn(final PsiTypeVariable n) {
                                                                                          final HashSet<PsiTypeVariable> in = ins.get(n);

                                                                                          if (in == null) {
                                                                                            return new HashSet<PsiTypeVariable>().iterator();
                                                                                          }

                                                                                          return in.iterator();
                                                                                        }

                                                                                        public Iterator<PsiTypeVariable> getOut(final PsiTypeVariable n) {
                                                                                          final HashSet<PsiTypeVariable> out = outs.get(n);

                                                                                          if (out == null) {
                                                                                            return new HashSet<PsiTypeVariable>().iterator();
                                                                                          }

                                                                                          return out.iterator();
                                                                                        }
                                                                                      });

    final LinkedList<Pair<Integer, Integer>> sccs = dfstBuilder.getSCCs();
    final HashMap<PsiTypeVariable, Integer> index = new HashMap<PsiTypeVariable, Integer>();

    for (Iterator<Pair<Integer, Integer>> i = sccs.iterator(); i.hasNext();) {
      final Pair<Integer, Integer> p = i.next();
      final Integer biT = p.getFirst();
      final int binum = biT.intValue();

      for (int j = 0; j < p.getSecond().intValue(); j++) {
        index.put(dfstBuilder.getNodeByTNumber(binum + j), biT);
      }
    }

    for (Iterator<Constraint> c = candidates.iterator(); c.hasNext();) {
      final Constraint constraint = c.next();

      if (index.get(constraint.getLeft()).equals(index.get(constraint.getRight()))) {
        myConstraints.remove(constraint);
      }
    }

    Binding binding = myBindingFactory.create();

    for (Iterator<PsiTypeVariable> v = index.keySet().iterator(); v.hasNext();) {
      final PsiTypeVariable fromVar = v.next();
      final PsiTypeVariable toVar = dfstBuilder.getNodeByNNumber(index.get(fromVar).intValue());

      if (!fromVar.equals(toVar)) {
        binding = binding.compose(myBindingFactory.create(fromVar, toVar));

        if (binding == null) {
        break;
        }
      }
    }

    if (binding != null && binding.nonEmpty()) {
      myCurrentBinding = myCurrentBinding.compose(binding);
      myConstraints = apply(binding);
    }
  }

  private void reduceTypeType(final Constraint constr) {
    final PsiType left = constr.getLeft();
    final PsiType right = constr.getRight();
    final HashSet<Constraint> addendum = new HashSet<Constraint>();

    int numSons = 0;
    Binding riseBinding = myBindingFactory.rise(left, right, null);
    if (riseBinding != null) numSons++;
    Binding sinkBinding = myBindingFactory.sink(left, right, null);
    if (sinkBinding != null) numSons++;
    Binding wcrdBinding = myCookWildcards ? myBindingFactory.riseWithWildcard(left, right, addendum) : null;
    if (wcrdBinding != null) numSons++;

    if (numSons == 0) return;

    if ((riseBinding != null && sinkBinding != null && riseBinding.equals(sinkBinding)) || canBePruned(riseBinding)) {
      numSons--;
      sinkBinding = null;
    }

    if (riseBinding != null && wcrdBinding != null && riseBinding.equals(wcrdBinding)){
      numSons--;
      wcrdBinding = null;
    }

    myConstraints.remove(constr);

    mySons = new ResolverTree[numSons];

    int n = 0;

    if (riseBinding != null) {
      mySons[n++] = applyRule(riseBinding);
    }

    if (sinkBinding != null) {
      mySons[n++] = applyRule(sinkBinding);
    }

    if (wcrdBinding != null) {
      mySons[n++] = applyRule(wcrdBinding, addendum);
    }
  }

  private void fillTypeRange(final PsiType lowerBound,
                             final PsiType upperBound,
                             final HashSet<PsiType> holder) {
    if (lowerBound instanceof PsiClassType && upperBound instanceof PsiClassType) {
      final PsiClassType.ClassResolveResult resultLower = ((PsiClassType)lowerBound).resolveGenerics();
      final PsiClassType.ClassResolveResult resultUpper = ((PsiClassType)upperBound).resolveGenerics();

      final PsiClass lowerClass = resultLower.getElement();
      final PsiClass upperClass = resultUpper.getElement();

      if (lowerClass != null && upperClass != null && !lowerClass.equals(upperClass)) {
        final PsiSubstitutor upperSubst = resultUpper.getSubstitutor();
        final PsiClass[] parents = upperClass.getSupers();
        final PsiElementFactory factory = PsiManager.getInstance(myProject).getElementFactory();

        for (int i = 0; i < parents.length; i++) {
          final PsiClass parent = parents[i];

          if (InheritanceUtil.isCorrectDescendant(parent, lowerClass, true)) {
            final PsiClassType type = factory.createType(parent,
                                                         TypeConversionUtil.getSuperClassSubstitutor(parent, upperClass, upperSubst));
            holder.add(type);
            fillTypeRange(lowerBound, type, holder);
          }
        }
      }
    }
    else if (lowerBound instanceof PsiArrayType && upperBound instanceof PsiArrayType) {
      fillTypeRange(((PsiArrayType)lowerBound).getComponentType(), ((PsiArrayType)upperBound).getComponentType(), holder);
    }
  }

  private PsiType[] getTypeRange(final PsiType lowerBound, final PsiType upperBound) {
    final HashSet<PsiType> range = new HashSet<PsiType>();

    range.add(lowerBound);
    range.add(upperBound);

    fillTypeRange(lowerBound, upperBound, range);

    return range.toArray(new PsiType[]{});
  }

  private void reduceInterval(final Constraint left, final Constraint right) {
    final PsiType leftType = left.getLeft();
    final PsiType rightType = right.getRight();
    final PsiTypeVariable var = (PsiTypeVariable)left.getRight();

    if (leftType.equals(rightType)) {
      final Binding binding = myBindingFactory.create(var, leftType);

      myConstraints.remove(left);
      myConstraints.remove(right);

      mySons = new ResolverTree[]{applyRule(binding)};

      return;
    }

    Binding riseBinding = myBindingFactory.rise(leftType, rightType, null);
    Binding sinkBinding = myBindingFactory.sink(leftType, rightType, null);

    int indicator = (riseBinding == null ? 0 : 1) + (sinkBinding == null ? 0 : 1);

    if (indicator == 0) {
      return;
    }
    else if ((indicator == 2 && riseBinding.equals(sinkBinding)) || canBePruned(riseBinding)) {
      indicator = 1;
      sinkBinding = null;
    }

    PsiType[] riseRange = PsiType.EMPTY_ARRAY;
    PsiType[] sinkRange = PsiType.EMPTY_ARRAY;

    if (riseBinding != null) {
      riseRange = getTypeRange(riseBinding.apply(rightType), riseBinding.apply(leftType));
    }

    if (sinkBinding != null) {
      sinkRange = getTypeRange(sinkBinding.apply(rightType), sinkBinding.apply(leftType));
    }

    if (riseRange.length + sinkRange.length > 0) {
      myConstraints.remove(left);
      myConstraints.remove(right);
    }

    mySons = new ResolverTree[riseRange.length + sinkRange.length];

    for (int i = 0; i < riseRange.length; i++) {
      final PsiType type = riseRange[i];

      mySons[i] = applyRule(riseBinding.compose(myBindingFactory.create(var, type)));
    }

    for (int i = 0; i < sinkRange.length; i++) {
      final PsiType type = sinkRange[i];

      mySons[i + riseRange.length] = applyRule(sinkBinding.compose(myBindingFactory.create(var, type)));
    }
  }

  private void reduce() {
    if (myConstraints.size() == 0) {
      return;
    }

    if (myCurrentBinding.isCyclic()) {
      reduceCyclicVariables();
    }

    final HashMap<PsiTypeVariable, Constraint> myTypeVarConstraints = new HashMap<PsiTypeVariable, Constraint>();
    final HashMap<PsiTypeVariable, Constraint> myVarTypeConstraints = new HashMap<PsiTypeVariable, Constraint>();

    for (Iterator<Constraint> i = myConstraints.iterator(); i.hasNext();) {
      final Constraint constr = i.next();

      final PsiType left = constr.getLeft();
      final PsiType right = constr.getRight();

      switch ((left instanceof PsiTypeVariable ? 0 : 1) + (right instanceof PsiTypeVariable ? 0 : 2)) {
      case 0:
      continue;

      case 1:
           {
             final Constraint c = myTypeVarConstraints.get(right);

             if (c == null) {
               final Constraint d = myVarTypeConstraints.get(right);

               if (d != null) {
                 reduceInterval(constr, d);
                 return;
               }

               myTypeVarConstraints.put((PsiTypeVariable)right, constr);
             }
             else {
               reduceTypeVar(constr, c);
               return;
             }
           }
      break;

      case 2:
           {
             final Constraint c = myVarTypeConstraints.get(left);

             if (c == null) {
               final Constraint d = myTypeVarConstraints.get(left);

               if (d != null) {
                 reduceInterval(d, constr);
                 return;
               }

               myVarTypeConstraints.put((PsiTypeVariable)left, constr);
             }
             else {
               reduceVarType(constr, c);
               return;
             }
           break;
           }

      case 3:
           reduceTypeType(constr);
           return;
      }
    }

    //T1 < a < b ... < T2
    if (myCookWildcards) {
      final HashSet<PsiTypeVariable> haveRightBound = new HashSet<PsiTypeVariable>();

      Constraint target = null;
      final HashSet<PsiTypeVariable> boundVariables = new HashSet<PsiTypeVariable>();

      for (final Iterator<Constraint> c = myConstraints.iterator(); c.hasNext();) {
        final Constraint constr = c.next();
        final PsiType leftType = constr.getLeft();
        final PsiType rightType = constr.getRight();

        if (rightType instanceof PsiTypeVariable) {
          boundVariables.add((PsiTypeVariable)rightType);

          if (leftType instanceof PsiTypeVariable) {
            boundVariables.add((PsiTypeVariable)leftType);
            haveRightBound.add(((PsiTypeVariable)leftType));
          }
          else if (!Util.bindsTypeVariables(leftType)) {
            target = constr;
          }
        }
      }

      if (target != null) {
        final PsiType type = target.getLeft();
        final PsiTypeVariable var = (PsiTypeVariable)target.getRight();

        final Binding binding =
          haveRightBound.contains(var) || type instanceof PsiWildcardType
          ? myBindingFactory.create(var, type)
          : myBindingFactory.create(var, PsiWildcardType.createSuper(PsiManager.getInstance(myProject), type));

        myConstraints.remove(target);

        mySons = new ResolverTree[]{applyRule(binding)};

        return;
      }
    } else {
      for (final Iterator<Constraint> c = myConstraints.iterator(); c.hasNext();) {
        final Constraint constr = c.next();
        final PsiType left = constr.getLeft();
        final PsiType right = constr.getRight();

        if (!(left instanceof PsiTypeVariable) && right instanceof PsiTypeVariable) {
          final HashSet<PsiTypeVariable> bound = new PsiTypeVarCollector().getSet(left);

          if (bound.contains(right)){
            myConstraints.remove(constr);
            mySons = new ResolverTree[]{applyRule(myBindingFactory.create(((PsiTypeVariable)right), Bottom.BOTTOM))};

            return;
          }

          final PsiManager manager = PsiManager.getInstance(myProject);
          final PsiType leftType = left instanceof PsiWildcardType ? ((PsiWildcardType)left).getBound() : left;
          final PsiType[] types = getTypeRange(PsiType.getJavaLangObject(manager, GlobalSearchScope.allScope(myProject)), leftType);

          mySons = new ResolverTree[types.length];

          if (types.length > 0) {
            myConstraints.remove(constr);
          }

          for (int i = 0; i < types.length; i++) {
            final PsiType type = types[i];
            mySons[i] = applyRule(myBindingFactory.create(((PsiTypeVariable)right), type));
          }

          return;
        }
      }
    }

    //T1 < a < b < ...
    {
      final HashSet<PsiTypeVariable> haveLeftBound = new HashSet<PsiTypeVariable>();

      Constraint target = null;
      final HashSet<PsiTypeVariable> boundVariables = new HashSet<PsiTypeVariable>();

      for (final Iterator<Constraint> c = myConstraints.iterator(); c.hasNext();) {
        final Constraint constr = c.next();
        final PsiType leftType = constr.getLeft();
        final PsiType rightType = constr.getRight();

        if (leftType instanceof PsiTypeVariable) {
          boundVariables.add((PsiTypeVariable)leftType);

          if (rightType instanceof PsiTypeVariable) {
            boundVariables.add((PsiTypeVariable)rightType);
            haveLeftBound.add(((PsiTypeVariable)rightType));
          }
          else if (!Util.bindsTypeVariables(rightType)) {
            target = constr;
          }
        }
      }

      if (target == null) {
        Binding binding = myBindingFactory.create();

        for (final Iterator<PsiTypeVariable> v = myBindingFactory.getBoundVariables().iterator(); v.hasNext();) {
          final PsiTypeVariable var = v.next();

          if (!myCurrentBinding.binds(var) && !boundVariables.contains(var)) {
            binding = binding.compose(myBindingFactory.create(var, Bottom.BOTTOM));
          }
        }

        if (!binding.nonEmpty()) {
          myConstraints.clear();
        }

        mySons = new ResolverTree[]{applyRule(binding)};
      }
      else {
        final PsiType type = target.getRight();
        final PsiTypeVariable var = (PsiTypeVariable)target.getLeft();

        final Binding binding =
          (haveLeftBound.contains(var) || type instanceof PsiWildcardType) || !myCookWildcards
          ? myBindingFactory.create(var, type)
          : myBindingFactory.create(var, PsiWildcardType.createExtends(PsiManager.getInstance(myProject), type));

        myConstraints.remove(target);

        mySons = new ResolverTree[]{applyRule(binding)};
      }
    }
  }

  private void logSolution() {
    LOG.debug("Reduced system:");

    for (final Iterator<Constraint> c = myConstraints.iterator(); c.hasNext();) {
      final Constraint constr = c.next();

      LOG.debug(constr.toString());
    }

    LOG.debug("End of Reduced system.");
    LOG.debug("Reduced binding:");
    LOG.debug(myCurrentBinding.toString());
    LOG.debug("End of Reduced binding.");
  }

  private interface Reducer {
    LinkedList<Pair<PsiType, Binding>> unify(PsiType x, PsiType y);

    Constraint create(PsiTypeVariable var, PsiType type);

    PsiType getType(Constraint c);

    PsiTypeVariable getVar(Constraint c);
  }

  private void reduceTypeVar(final Constraint x, final Constraint y) {
    reduceSideVar(x, y, new Reducer() {
                    public LinkedList<Pair<PsiType, Binding>> unify(final PsiType x, final PsiType y) {
                      return myBindingFactory.intersect(x, y);
                    }

                    public Constraint create(final PsiTypeVariable var, final PsiType type) {
                      return new Subtype(type, var);
                    }

                    public PsiType getType(final Constraint c) {
                      return c.getLeft();
                    }

                    public PsiTypeVariable getVar(final Constraint c) {
                      return (PsiTypeVariable)c.getRight();
                    }
                  });
  }

  private void reduceVarType(final Constraint x, final Constraint y) {
    reduceSideVar(x, y, new Reducer() {
                    public LinkedList<Pair<PsiType, Binding>> unify(final PsiType x, final PsiType y) {
                      return myBindingFactory.union(x, y);
                    }

                    public Constraint create(final PsiTypeVariable var, final PsiType type) {
                      return new Subtype(var, type);
                    }

                    public PsiType getType(final Constraint c) {
                      return c.getRight();
                    }

                    public PsiTypeVariable getVar(final Constraint c) {
                      return (PsiTypeVariable)c.getLeft();
                    }
                  });
  }

  private void reduceSideVar(final Constraint x, final Constraint y, final Reducer reducer) {
    final PsiTypeVariable var = reducer.getVar(x);

    final PsiType xType = reducer.getType(x);
    final PsiType yType = reducer.getType(y);

    final LinkedList<Pair<PsiType, Binding>> union = reducer.unify(xType, yType);

    if (union.size() == 0) {
      return;
    }

    myConstraints.remove(x);
    myConstraints.remove(y);

    mySons = new ResolverTree[union.size()];
    int i = 0;

    Constraint prev = null;

    for (Iterator<Pair<PsiType, Binding>> p = union.iterator(); p.hasNext();) {
      final Pair<PsiType, Binding> pair = p.next();

      if (prev != null) {
        myConstraints.remove(prev);
      }

      prev = reducer.create(var, pair.getFirst());
      myConstraints.add(prev);

      mySons[i++] = applyRule(pair.getSecond());
    }
  }

  public void resolve() {
    reduce();

    if (mySons.length > 0) {
      for (int i = 0; i < mySons.length; i++) {

        if (mySons[i] != null) {
          mySons[i].resolve();
          mySons[i] = null;
        }
      }
    }
    else {
      if (myConstraints.size() == 0) {
        logSolution();

        mySolutions.putSolution(myCurrentBinding);
      }
    }
  }

  public Binding getBestSolution() {
    return mySolutions.getBestSolution();
  }
}
