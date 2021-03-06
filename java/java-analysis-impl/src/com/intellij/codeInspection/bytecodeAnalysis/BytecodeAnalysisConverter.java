/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInspection.bytecodeAnalysis;

import com.intellij.codeInspection.dataFlow.MethodContract.ValueConstraint;
import com.intellij.codeInspection.dataFlow.StandardMethodContract;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.ThreadLocalCachedValue;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static com.intellij.codeInspection.bytecodeAnalysis.Direction.*;
import static com.intellij.codeInspection.bytecodeAnalysis.ProjectBytecodeAnalysis.LOG;

/**
 * @author lambdamix
 */
public class BytecodeAnalysisConverter {

  // how many bytes are taken from class fqn digest
  public static final int CLASS_HASH_SIZE = 10;
  // how many bytes are taken from signature digest
  public static final int SIGNATURE_HASH_SIZE = 4;
  public static final int HASH_SIZE = CLASS_HASH_SIZE + SIGNATURE_HASH_SIZE;

  private static final ThreadLocalCachedValue<MessageDigest> HASHER_CACHE = new ThreadLocalCachedValue<MessageDigest>() {
    @Override
    public MessageDigest create() {
      try {
        return MessageDigest.getInstance("MD5");
      } catch (NoSuchAlgorithmException exception) {
        throw new RuntimeException(exception);
      }
    }

    @Override
    protected void init(MessageDigest value) {
      value.reset();
    }
  };

  public static MessageDigest getMessageDigest() {
    return HASHER_CACHE.getValue();
  }

  /**
   * Converts an equation over asm keys into equation over small hash keys.
   */
  @NotNull
  static DirectionResultPair convert(@NotNull Equation equation, @NotNull MessageDigest md) {
    ProgressManager.checkCanceled();

    Result rhs = equation.rhs;
    HResult hResult;
    if (rhs instanceof Final) {
      hResult = new HFinal(((Final)rhs).value);
    }
    else if (rhs instanceof Pending) {
      Pending pending = (Pending)rhs;
      Set<Product> sumOrigin = pending.sum;
      HComponent[] components = new HComponent[sumOrigin.size()];
      int componentI = 0;
      for (Product prod : sumOrigin) {
        HKey[] intProd = new HKey[prod.ids.size()];
        int idI = 0;
        for (Key key : prod.ids) {
          intProd[idI] = asmKey(key, md);
          idI++;
        }
        HComponent intIdComponent = new HComponent(prod.value, intProd);
        components[componentI] = intIdComponent;
        componentI++;
      }
      hResult = new HPending(components);
    } else {
      Effects wrapper = (Effects)rhs;
      Set<EffectQuantum> effects = wrapper.effects;
      Set<HEffectQuantum> hEffects = new HashSet<>();
      for (EffectQuantum effect : effects) {
        if (effect == EffectQuantum.TopEffectQuantum) {
          hEffects.add(HEffectQuantum.TopEffectQuantum);
        }
        else if (effect == EffectQuantum.ThisChangeQuantum) {
          hEffects.add(HEffectQuantum.ThisChangeQuantum);
        }
        else if (effect instanceof EffectQuantum.ParamChangeQuantum) {
          EffectQuantum.ParamChangeQuantum paramChangeQuantum = (EffectQuantum.ParamChangeQuantum)effect;
          hEffects.add(new HEffectQuantum.ParamChangeQuantum(paramChangeQuantum.n));
        }
        else if (effect instanceof EffectQuantum.CallQuantum) {
          EffectQuantum.CallQuantum callQuantum = (EffectQuantum.CallQuantum)effect;
          hEffects.add(new HEffectQuantum.CallQuantum(asmKey(callQuantum.key, md), callQuantum.data, callQuantum.isStatic));
        }
      }
      hResult = new HEffects(hEffects);
    }
    return new DirectionResultPair(equation.id.direction.asInt(), hResult);
  }

  /**
   * Converts an asm method key to a small hash key (HKey)
   */
  @NotNull
  public static HKey asmKey(@NotNull Key key, @NotNull MessageDigest md) {
    byte[] classDigest = md.digest(key.method.internalClassName.getBytes(CharsetToolkit.UTF8_CHARSET));
    md.update(key.method.methodName.getBytes(CharsetToolkit.UTF8_CHARSET));
    md.update(key.method.methodDesc.getBytes(CharsetToolkit.UTF8_CHARSET));
    byte[] sigDigest = md.digest();
    byte[] digest = new byte[HASH_SIZE];
    System.arraycopy(classDigest, 0, digest, 0, CLASS_HASH_SIZE);
    System.arraycopy(sigDigest, 0, digest, CLASS_HASH_SIZE, SIGNATURE_HASH_SIZE);
    return new HKey(digest, key.direction.asInt(), key.stable, key.negated);
  }

  /**
   * Converts a Psi method to a small hash key (HKey).
   * Returns null if conversion is impossible (something is not resolvable).
   */
  @Nullable
  public static HKey psiKey(@NotNull PsiMethod psiMethod, @NotNull Direction direction, @NotNull MessageDigest md) {
    final PsiClass psiClass = PsiTreeUtil.getParentOfType(psiMethod, PsiClass.class, false);
    if (psiClass == null) {
      return null;
    }
    byte[] classDigest = psiClassDigest(psiClass, md);
    if (classDigest == null) {
      return null;
    }
    byte[] sigDigest = methodDigest(psiMethod, md);
    if (sigDigest == null) {
      return null;
    }
    byte[] digest = new byte[HASH_SIZE];
    System.arraycopy(classDigest, 0, digest, 0, CLASS_HASH_SIZE);
    System.arraycopy(sigDigest, 0, digest, CLASS_HASH_SIZE, SIGNATURE_HASH_SIZE);
    return new HKey(digest, direction.asInt(), true, false);
  }

  @Nullable
  private static byte[] psiClassDigest(@NotNull PsiClass psiClass, @NotNull MessageDigest md) {
    String descriptor = descriptor(psiClass, 0, false);
    if (descriptor == null) {
      return null;
    }
    return md.digest(descriptor.getBytes(CharsetToolkit.UTF8_CHARSET));
  }

  @Nullable
  private static byte[] methodDigest(@NotNull PsiMethod psiMethod, @NotNull MessageDigest md) {
    String descriptor = descriptor(psiMethod);
    if (descriptor == null) {
      return null;
    }
    return md.digest(descriptor.getBytes(CharsetToolkit.UTF8_CHARSET));
  }

  @Nullable
  private static String descriptor(@NotNull PsiMethod psiMethod) {
    StringBuilder sb = new StringBuilder();
    final PsiClass psiClass = PsiTreeUtil.getParentOfType(psiMethod, PsiClass.class, false);
    if (psiClass == null) {
      return null;
    }
    PsiClass outerClass = psiClass.getContainingClass();
    boolean isInnerClassConstructor = psiMethod.isConstructor() && (outerClass != null) && !psiClass.hasModifierProperty(PsiModifier.STATIC);
    PsiParameter[] parameters = psiMethod.getParameterList().getParameters();
    PsiType returnType = psiMethod.getReturnType();

    sb.append(returnType == null ? "<init>" : psiMethod.getName());
    sb.append('(');

    String desc;

    if (isInnerClassConstructor) {
      desc = descriptor(outerClass, 0, true);
      if (desc == null) {
        return null;
      }
      sb.append(desc);
    }
    for (PsiParameter parameter : parameters) {
      desc = descriptor(parameter.getType());
      if (desc == null) {
        return null;
      }
      sb.append(desc);
    }
    sb.append(')');
    if (returnType == null) {
      sb.append('V');
    } else {
      desc = descriptor(returnType);
      if (desc == null) {
        return null;
      } else {
        sb.append(desc);
      }
    }
    return sb.toString();
  }

  @Nullable
  private static String descriptor(@NotNull PsiClass psiClass, int dimensions, boolean full) {
    PsiFile containingFile = psiClass.getContainingFile();
    if (!(containingFile instanceof PsiClassOwner)) {
      LOG.debug("containingFile was not resolved for " + psiClass.getQualifiedName());
      return null;
    }
    PsiClassOwner psiFile = (PsiClassOwner)containingFile;
    String packageName = psiFile.getPackageName();
    String qname = psiClass.getQualifiedName();
    if (qname == null) {
      return null;
    }
    String className;
    if (packageName.length() > 0) {
      className = qname.substring(packageName.length() + 1).replace('.', '$');
    } else {
      className = qname.replace('.', '$');
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < dimensions; i++) {
      sb.append('[');
    }
    if (full) {
      sb.append('L');
    }
    if (packageName.length() > 0) {
      sb.append(packageName.replace('.', '/'));
      sb.append('/');
    }
    sb.append(className);
    if (full) {
      sb.append(';');
    }
    return sb.toString();
  }

  @Nullable
  private static String descriptor(@NotNull PsiType psiType) {
    int dimensions = 0;
    psiType = TypeConversionUtil.erasure(psiType);
    if (psiType instanceof PsiArrayType) {
      PsiArrayType arrayType = (PsiArrayType)psiType;
      psiType = arrayType.getDeepComponentType();
      dimensions = arrayType.getArrayDimensions();
    }

    if (psiType instanceof PsiClassType) {
      PsiClass psiClass = ((PsiClassType)psiType).resolve();
      if (psiClass != null) {
        return descriptor(psiClass, dimensions, true);
      }
      else {
        LOG.debug("resolve was null for " + psiType.getCanonicalText());
        return null;
      }
    }
    else if (psiType instanceof PsiPrimitiveType) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < dimensions; i++) {
         sb.append('[');
      }
      if (PsiType.VOID.equals(psiType)) {
        sb.append('V');
      }
      else if (PsiType.BOOLEAN.equals(psiType)) {
        sb.append('Z');
      }
      else if (PsiType.CHAR.equals(psiType)) {
        sb.append('C');
      }
      else if (PsiType.BYTE.equals(psiType)) {
        sb.append('B');
      }
      else if (PsiType.SHORT.equals(psiType)) {
        sb.append('S');
      }
      else if (PsiType.INT.equals(psiType)) {
        sb.append('I');
      }
      else if (PsiType.FLOAT.equals(psiType)) {
        sb.append('F');
      }
      else if (PsiType.LONG.equals(psiType)) {
        sb.append('J');
      }
      else if (PsiType.DOUBLE.equals(psiType)) {
        sb.append('D');
      }
      return sb.toString();
    }
    return null;
  }


  /**
   * Given a PSI method and its primary HKey enumerate all contract keys for it.
   *
   * @param psiMethod psi method
   * @param primaryKey primary stable keys
   * @return corresponding (stable!) keys
   */
  @NotNull
  public static ArrayList<HKey> mkInOutKeys(@NotNull PsiMethod psiMethod, @NotNull HKey primaryKey) {
    PsiParameter[] parameters = psiMethod.getParameterList().getParameters();
    ArrayList<HKey> keys = new ArrayList<>(parameters.length * 2 + 2);
    keys.add(primaryKey);
    for (int i = 0; i < parameters.length; i++) {
      if (!(parameters[i].getType() instanceof PsiPrimitiveType)) {
        keys.add(primaryKey.withDirection(new InOut(i, Value.NotNull)));
        keys.add(primaryKey.withDirection(new InOut(i, Value.Null)));
        keys.add(primaryKey.withDirection(new InThrow(i, Value.NotNull)));
        keys.add(primaryKey.withDirection(new InThrow(i, Value.Null)));
      } else if (PsiType.BOOLEAN.equals(parameters[i].getType())) {
        keys.add(primaryKey.withDirection(new InOut(i, Value.True)));
        keys.add(primaryKey.withDirection(new InOut(i, Value.False)));
        keys.add(primaryKey.withDirection(new InThrow(i, Value.True)));
        keys.add(primaryKey.withDirection(new InThrow(i, Value.False)));
      }
    }
    return keys;
  }

  /**
   * Given `solution` of all dependencies of a method with the `methodKey`, converts this solution into annotations.
   *
   * @param solution solution of equations
   * @param methodAnnotations annotations to which corresponding solutions should be added
   * @param methodKey a primary key of a method being analyzed. not it is stable
   * @param arity arity of this method (hint for constructing @Contract annotations)
   */
  public static void addMethodAnnotations(@NotNull Map<HKey, Value> solution, @NotNull MethodAnnotations methodAnnotations, @NotNull HKey methodKey, int arity) {
    List<StandardMethodContract> contractClauses = new ArrayList<>();
    Set<HKey> notNulls = methodAnnotations.notNulls;
    Set<HKey> pures = methodAnnotations.pures;
    Map<HKey, String> contracts = methodAnnotations.contractsValues;

    for (Map.Entry<HKey, Value> entry : solution.entrySet()) {
      // NB: keys from Psi are always stable, so we need to stabilize keys from equations
      Value value = entry.getValue();
      if (value == Value.Top || value == Value.Bot || (value == Value.Fail && !pures.contains(methodKey))) {
        continue;
      }
      HKey key = entry.getKey().mkStable();
      Direction direction = key.getDirection();
      HKey baseKey = key.mkBase();
      if (!methodKey.equals(baseKey)) {
        continue;
      }
      if (value == Value.NotNull && direction == Out) {
        notNulls.add(methodKey);
      }
      else if (value == Value.Pure && direction == Pure) {
        pures.add(methodKey);
      }
      else if (direction instanceof ParamValueBasedDirection) {
        contractClauses.add(contractElement(arity, (ParamValueBasedDirection)direction, value));
      }
    }

    // no contract clauses for @NotNull methods
    if (!notNulls.contains(methodKey) && !contractClauses.isEmpty()) {
      Map<Boolean, List<StandardMethodContract>> partition =
        StreamEx.of(contractClauses).partitioningBy(c -> c.getReturnValue() == ValueConstraint.THROW_EXCEPTION);
      List<StandardMethodContract> failingContracts = squashContracts(partition.get(true));
      List<StandardMethodContract> nonFailingContracts = squashContracts(partition.get(false));
      // Sometimes "null,_->!null;!null,_->!null" contracts are inferred for some reason
      // They are squashed to "_,_->!null" which is better expressed as @NotNull annotation
      if(nonFailingContracts.size() == 1) {
        StandardMethodContract contract = nonFailingContracts.get(0);
        if(contract.getReturnValue() == ValueConstraint.NOT_NULL_VALUE && contract.isTrivial()) {
          nonFailingContracts = Collections.emptyList();
          notNulls.add(methodKey);
        }
      }
      // Failing contracts go first
      String result = StreamEx.of(failingContracts, nonFailingContracts)
        .flatMap(list -> list.stream()
          .map(Object::toString)
          .map(str -> str.replace(" ", "")) // for compatibility with existing tests
          .sorted())
        .joining(";");
      if(!result.isEmpty()) {
        contracts.put(methodKey, '"'+result+'"');
      }
    }

  }

  @NotNull
  private static List<StandardMethodContract> squashContracts(List<StandardMethodContract> contractClauses) {
    // If there's a pair of contracts yielding the same value like "null,_->true", "!null,_->true"
    // then trivial contract should be used like "_,_->true"
    StandardMethodContract soleContract = StreamEx.ofPairs(contractClauses, (c1, c2) -> {
      if (c1.getReturnValue() != c2.getReturnValue()) return null;
      int idx = -1;
      for (int i = 0; i < c1.arguments.length; i++) {
        ValueConstraint left = c1.arguments[i];
        ValueConstraint right = c2.arguments[i];
        if(left == ValueConstraint.ANY_VALUE && right == ValueConstraint.ANY_VALUE) continue;
        if(idx >= 0) return null;
        if(left == ValueConstraint.NOT_NULL_VALUE && right == ValueConstraint.NULL_VALUE ||
           left == ValueConstraint.NULL_VALUE && right == ValueConstraint.NOT_NULL_VALUE ||
           left == ValueConstraint.TRUE_VALUE && right == ValueConstraint.FALSE_VALUE ||
           left == ValueConstraint.FALSE_VALUE && right == ValueConstraint.TRUE_VALUE) {
          idx = i;
        } else {
          return null;
        }
      }
      return c1;
    }).nonNull().findFirst().orElse(null);
    if(soleContract != null) {
      Arrays.fill(soleContract.arguments, ValueConstraint.ANY_VALUE);
      contractClauses = Collections.singletonList(soleContract);
    }
    return contractClauses;
  }

  public static void addEffectAnnotations(Map<HKey, Set<HEffectQuantum>> puritySolutions,
                                          MethodAnnotations result,
                                          HKey methodKey,
                                          boolean constructor) {
    for (Map.Entry<HKey, Set<HEffectQuantum>> entry : puritySolutions.entrySet()) {
      Set<HEffectQuantum> effects = entry.getValue();
      HKey key = entry.getKey().mkStable();
      HKey baseKey = key.mkBase();
      if (!methodKey.equals(baseKey)) {
        continue;
      }
      if (effects.isEmpty() || (constructor && effects.size() == 1 && effects.contains(HEffectQuantum.ThisChangeQuantum))) {
        // Pure constructor is allowed to change "this" object as this is a new object anyways
        result.pures.add(methodKey);
      }
    }
  }

  private static StandardMethodContract contractElement(int arity, ParamValueBasedDirection inOut, Value value) {
    final ValueConstraint[] constraints = new ValueConstraint[arity];
    Arrays.fill(constraints, ValueConstraint.ANY_VALUE);
    constraints[inOut.paramIndex] = inOut.inValue.toValueConstraint();
    return new StandardMethodContract(constraints, value.toValueConstraint());
  }

}
