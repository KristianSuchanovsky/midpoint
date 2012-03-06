/*
 * Copyright (c) 2012 Evolveum
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
 *
 * Portions Copyrighted 2012 [name of copyright owner]
 */

package com.evolveum.midpoint.repo.sql.data.atest;

import org.hibernate.annotations.Columns;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.xml.namespace.QName;
import java.io.Serializable;

/**
 * Created by IntelliJ IDEA.
 * User: lazyman
 * Date: 3/3/12
 * Time: 12:23 PM
 * To change this template use File | Settings | File Templates.
 */
@Embeddable
public class Reference implements Serializable {

    private QName type;

    @Columns(columns = {
            @Column(name = "namespaceURI"),
            @Column(name = "localPart")
    })
    public QName getType() {
        return type;
    }

    public void setType(QName type) {
        this.type = type;
    }
}
