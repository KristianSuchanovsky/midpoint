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
package com.evolveum.midpoint.model.synchronizer;

import com.evolveum.midpoint.common.refinery.ResourceAccountType;
import com.evolveum.midpoint.common.valueconstruction.ValueConstruction;
import com.evolveum.midpoint.common.valueconstruction.ValueConstructionFactory;
import com.evolveum.midpoint.model.AccountSyncContext;
import com.evolveum.midpoint.model.PolicyDecision;
import com.evolveum.midpoint.model.SyncContext;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismObjectDefinition;
import com.evolveum.midpoint.prism.PrismProperty;
import com.evolveum.midpoint.prism.PrismPropertyDefinition;
import com.evolveum.midpoint.prism.PrismPropertyValue;
import com.evolveum.midpoint.prism.delta.ChangeType;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.delta.PropertyDelta;
import com.evolveum.midpoint.prism.schema.PrismSchema;
import com.evolveum.midpoint.prism.schema.SchemaRegistry;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.processor.*;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.util.exception.ExpressionEvaluationException;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.common_1.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @author semancik
 */
@Component
public class ActivationProcessor {

    private static final Trace LOGGER = TraceManager.getTrace(ActivationProcessor.class);

    @Autowired(required = true)
    private PrismContext prismContext;

    @Autowired(required = true)
    private ValueConstructionFactory valueConstructionFactory;

    public void processActivation(SyncContext context, OperationResult result) throws ExpressionEvaluationException, ObjectNotFoundException, SchemaException {

        ObjectDelta<UserType> userDelta = context.getUserDelta();
        if (userDelta == null) {
            // This must be a user delete or something similar. No point in proceeding
            LOGGER.trace("userDelta is null, skipping activation processing");
            return;
        }
        PropertyDelta enabledValueDelta = userDelta.findPropertyDelta(SchemaConstants.PATH_ACTIVATION_ENABLE);

        PrismObject<UserType> userNew = context.getUserNew();
        if (userNew == null) {
            // This must be a user delete or something similar. No point in proceeding
            LOGGER.trace("userNew is null, skipping activation processing");
            return;
        }
        PrismProperty userEnabledNew = userNew.findProperty(SchemaConstants.PATH_ACTIVATION_ENABLE);

        PrismObjectDefinition<AccountShadowType> accountDefinition = 
        	prismContext.getSchemaRegistry().findObjectDefinitionByCompileTimeClass(AccountShadowType.class);
        PrismPropertyDefinition accountEnabledPropertyDefinition = 
        	accountDefinition.findPropertyDefinition(SchemaConstants.PATH_ACTIVATION_ENABLE);

        for (AccountSyncContext accCtx : context.getAccountContexts()) {
            ResourceAccountType rat = accCtx.getResourceAccountType();
            
            PolicyDecision policyDecision = accCtx.getPolicyDecision();
            if (policyDecision != null && (policyDecision == PolicyDecision.DELETE || policyDecision == PolicyDecision.UNLINK)) {
                LOGGER.trace("Activation processing skipped for " + rat + ", account is being deleted or unlinked");
                continue;
            }

            ObjectDelta<AccountShadowType> accountDelta = accCtx.getAccountDelta();
            if (accountDelta != null && accountDelta.getChangeType() == ChangeType.ADD) {
                // adding new account, synchronize activation regardless whether the user activation was changed or not.
            } else if (enabledValueDelta != null) {
                // user activation was changed. synchronize it regardless of the account change.
            } else {
                LOGGER.trace("No change in activation and the account is not added, skipping activation processing for account " + rat);
                continue;
            }

            ResourceAccountTypeDefinitionType resourceAccountDefType = accCtx.getResourceAccountTypeDefinitionType();
            if (resourceAccountDefType == null) {
                LOGGER.trace("No ResourceAccountTypeDefinition, therefore also no activation outbound definition, skipping activation processing for account " + rat);
                continue;
            }
            ResourceActivationDefinitionType activationType = resourceAccountDefType.getActivation();
            if (activationType == null) {
                LOGGER.trace("No activation definition in account type {}, skipping activation processing", rat);
                continue;
            }
            ResourceActivationEnableDefinitionType enabledType = activationType.getEnabled();
            if (enabledType == null) {
                LOGGER.trace("No 'enabled' definition in activation in account type {}, skipping activation processing", rat);
                continue;
            }
            ValueConstructionType outbound = enabledType.getOutbound();
            if (outbound == null) {
                LOGGER.trace("No outbound definition in 'enabled' definition in activation in account type {}, skipping activation processing", rat);
                continue;
            }
            
            ValueConstruction<PrismPropertyValue<Boolean>> enabledConstruction =
            	valueConstructionFactory.createValueConstruction(outbound, accountEnabledPropertyDefinition, 
            		"outbound activation in account type " + rat);
            enabledConstruction.setInput(userEnabledNew);
            enabledConstruction.evaluate(result);
            PrismProperty<Boolean> accountEnabledNew = (PrismProperty<Boolean>) enabledConstruction.getOutput();
            if (accountEnabledNew == null || accountEnabledNew.isEmpty()) {
                LOGGER.trace("Activation 'enable' expression resulted in null or empty value, skipping activation processing for {}", rat);
                continue;
            }
            PropertyDelta accountEnabledDelta = PropertyDelta.createDelta(SchemaConstants.PATH_ACTIVATION_ENABLE, AccountShadowType.class, prismContext);
            accountEnabledDelta.setValuesToReplace(accountEnabledNew.getValues());
            LOGGER.trace("Adding new 'enabled' delta for account {}: {}", rat, accountEnabledNew.getValues());
            accCtx.addToSecondaryDelta(accountEnabledDelta);

        }

    }

}
