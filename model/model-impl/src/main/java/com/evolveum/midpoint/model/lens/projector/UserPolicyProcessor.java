/**
 * Copyright (c) 2011 Evolveum
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://www.opensource.org/licenses/cddl1 or
 * CDDLv1.0.txt file in the source code distribution.
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * Portions Copyrighted 2011 [name of copyright owner]
 */
package com.evolveum.midpoint.model.lens.projector;

import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.evolveum.midpoint.common.crypto.EncryptionException;
import com.evolveum.midpoint.common.crypto.Protector;
import com.evolveum.midpoint.common.password.PasswordPolicyUtils;
import com.evolveum.midpoint.common.valueconstruction.ValueConstruction;
import com.evolveum.midpoint.common.valueconstruction.ValueConstructionFactory;
import com.evolveum.midpoint.model.api.PolicyViolationException;
import com.evolveum.midpoint.model.lens.LensContext;
import com.evolveum.midpoint.model.lens.LensFocusContext;
import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismObjectDefinition;
import com.evolveum.midpoint.prism.PrismProperty;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.PropertyPath;
import com.evolveum.midpoint.prism.delta.ChangeType;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.delta.PrismValueDeltaSetTriple;
import com.evolveum.midpoint.prism.delta.PropertyDelta;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.constants.ExpressionConstants;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.holder.XPathHolder;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.util.ObjectTypeUtil;
import com.evolveum.midpoint.util.exception.ExpressionEvaluationException;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.exception.SystemException;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.common_2.AccountShadowType;
import com.evolveum.midpoint.xml.ns._public.common.common_2.CredentialsType;
import com.evolveum.midpoint.xml.ns._public.common.common_2.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_2.PasswordPolicyType;
import com.evolveum.midpoint.xml.ns._public.common.common_2.PasswordType;
import com.evolveum.midpoint.xml.ns._public.common.common_2.PropertyConstructionType;
import com.evolveum.midpoint.xml.ns._public.common.common_2.ProtectedStringType;
import com.evolveum.midpoint.xml.ns._public.common.common_2.UserTemplateType;
import com.evolveum.midpoint.xml.ns._public.common.common_2.UserType;
import com.evolveum.midpoint.xml.ns._public.common.common_2.ValueConstructionType;

/**
 * Processor to handle user template and possible also other user "policy"
 * elements.
 * 
 * @author Radovan Semancik
 * 
 */
@Component
public class UserPolicyProcessor {

	private static final Trace LOGGER = TraceManager.getTrace(UserPolicyProcessor.class);

	@Autowired(required = true)
	private ValueConstructionFactory valueConstructionFactory;

	@Autowired(required = true)
	private PrismContext prismContext;

	@Autowired(required = true)
	private PasswordPolicyProcessor passwordPolicyProcessor;

	@Autowired(required = true)
	@Qualifier("cacheRepositoryService")
	private transient RepositoryService cacheRepositoryService;

	<F extends ObjectType, P extends ObjectType> void processUserPolicy(LensContext<F,P> context, OperationResult result) throws ObjectNotFoundException,
            SchemaException, ExpressionEvaluationException, PolicyViolationException {

		LensFocusContext<F> focusContext = context.getFocusContext();
    	if (focusContext == null) {
    		return;
    	}
    	
    	if (focusContext.getObjectTypeClass() != UserType.class) {
    		// We can do this only for user.
    		return;
    	}
    	
		LensContext<UserType, AccountShadowType> usContext = (LensContext<UserType, AccountShadowType>) context;
    	//check user password if satisfies policies
		
//		PrismProperty<PasswordType> password = getPasswordValue((LensFocusContext<UserType>)focusContext);
		
//		if (password != null) {
			passwordPolicyProcessor.processPasswordPolicy((LensFocusContext<UserType>) focusContext, usContext, result);
//		}

		applyUserTemplate(usContext, result);
				
	}

	
	private void applyUserTemplate(LensContext<UserType, AccountShadowType> context,
			OperationResult result) throws ExpressionEvaluationException, ObjectNotFoundException, SchemaException {
		LensFocusContext<UserType> focusContext = context.getFocusContext();

		UserTemplateType userTemplate = context.getUserTemplate();

		if (userTemplate == null) {
			// No applicable template
			LOGGER.trace("Skipping processing of user template: no user template");
			return;
		}
		
		LOGGER.trace("Applying " + userTemplate + " to " + focusContext.getObjectNew());

		ObjectDelta<UserType> userSecondaryDelta = focusContext.getWaveSecondaryDelta();
		ObjectDelta<UserType> userPrimaryDelta = focusContext.getWavePrimaryDelta();
		for (PropertyConstructionType propConstr : userTemplate.getPropertyConstruction()) {
			XPathHolder propertyXPath = new XPathHolder(propConstr.getProperty());
			PropertyPath itemPath = propertyXPath.toPropertyPath();

			PrismObjectDefinition<UserType> userDefinition = getUserDefinition();
			ItemDefinition itemDefinition = userDefinition.findItemDefinition(itemPath);
			if (itemDefinition == null) {
				throw new SchemaException("The property " + itemPath + " is not a valid user property, defined in "
						+ ObjectTypeUtil.toShortString(userTemplate));
			}

			ValueConstructionType valueConstructionType = propConstr.getValueConstruction();
			// TODO: is the parentPath correct (null)?
			ValueConstruction valueConstruction = valueConstructionFactory.createValueConstruction(
					valueConstructionType, itemDefinition, "user template expression for " + itemDefinition.getName()
							+ " while processing user " + focusContext.getObjectNew());

			PrismProperty existingUserProperty = focusContext.getObjectNew().findProperty(itemPath);
			if (existingUserProperty != null && !existingUserProperty.isEmpty() && valueConstruction.isInitial()) {
				// This valueConstruction only applies if the property does not have a value yet.
				// ... but it does
				continue;
			}

			evaluateUserTemplateValueConstruction(valueConstruction, itemDefinition, context, result);

			PrismValueDeltaSetTriple<? extends PrismValue> outputTriple = valueConstruction.getOutputTriple();
			if (outputTriple != null) {
				ItemDelta itemDelta = valueConstruction.createEmptyDelta(itemPath);
				Collection<? extends PrismValue> nonNegativeValues = outputTriple.getNonNegativeValues();
				if (itemDefinition.isMultiValue()) {
					for (PrismValue value: nonNegativeValues) {
						if (!hasValue(existingUserProperty, value)) {
							itemDelta.addValueToAdd(value.clone());
						}
					}
					// TODO: remove values
				} else {
					if (nonNegativeValues.size() > 1) {
						throw new SchemaException("Attempt to store "+nonNegativeValues.size()+" values in single-valued user property "+itemPath);
					}
					if (nonNegativeValues.size() == 0) {
						if (existingUserProperty != null && !existingUserProperty.isEmpty()) {
							// Empty set in replace value will cause the property to remove all existing values.
							itemDelta.setValuesToReplace(nonNegativeValues);
						}
					} else {
						PrismValue value = nonNegativeValues.iterator().next();
						if (!hasValue(existingUserProperty, value)) {
							itemDelta.setValueToReplace(value.clone());
						}
					}
				}
				if (!userPrimaryDelta.containsModification(itemDelta)) {
					if (!itemDelta.isEmpty()) {
						if (userSecondaryDelta == null) {
							userSecondaryDelta = new ObjectDelta<UserType>(UserType.class, ChangeType.MODIFY);
							focusContext.setWaveSecondaryDelta(userSecondaryDelta);
						}
						userSecondaryDelta.addModification(itemDelta);	
					}
				}
			}
		}

	}

	private boolean hasValue(PrismProperty existingUserProperty, PrismValue newValue) {
		if (existingUserProperty == null) {
			return false;
		}
		// TODO: is this OK? Will it not mess with value meta-data?
		return existingUserProperty.contains(newValue);
	}


	private PrismObjectDefinition<UserType> getUserDefinition() {
		return prismContext.getSchemaRegistry().getObjectSchema()
				.findObjectDefinitionByCompileTimeClass(UserType.class);
	}

	private void evaluateUserTemplateValueConstruction(ValueConstruction valueConstruction,
			ItemDefinition propertyDefinition, LensContext<UserType, AccountShadowType> context, OperationResult result)
			throws ExpressionEvaluationException, ObjectNotFoundException, SchemaException {

		valueConstruction.addVariableDefinition(ExpressionConstants.VAR_USER, context.getFocusContext().getObjectNew());
		// TODO: variables
		// TODO: root node

		valueConstruction.evaluate(result);

	}

}
