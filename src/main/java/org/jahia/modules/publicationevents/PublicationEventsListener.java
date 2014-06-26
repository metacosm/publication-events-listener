/**
 * This file is part of Jahia, next-generation open source CMS:
 * Jahia's next-generation, open source CMS stems from a widely acknowledged vision
 * of enterprise application convergence - web, search, document, social and portal -
 * unified by the simplicity of web content management.
 *
 * For more information, please visit http://www.jahia.com.
 *
 * Copyright (C) 2002-2013 Jahia Solutions Group SA. All rights reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 *
 * As a special exception to the terms and conditions of version 2.0 of
 * the GPL (or any later version), you may redistribute this Program in connection
 * with Free/Libre and Open Source Software ("FLOSS") applications as described
 * in Jahia's FLOSS exception. You should have received a copy of the text
 * describing the FLOSS exception, and it is also available here:
 * http://www.jahia.com/license
 *
 * Commercial and Supported Versions of the program (dual licensing):
 * alternatively, commercial and supported versions of the program may be used
 * in accordance with the terms and conditions contained in a separate
 * written agreement between you and Jahia Solutions Group SA.
 *
 * If you are unsure which license is appropriate for your use,
 * please contact the sales department at sales@jahia.com.
 */
package org.jahia.modules.publicationevents;

import org.jahia.registries.ServicesRegistry;
import org.jahia.services.content.*;
import org.jahia.services.usermanager.JahiaGroupManagerService;

import javax.jcr.*;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import java.security.Principal;
import java.util.*;

/**
 * @author Christophe Laprun
 */
public class PublicationEventsListener extends DefaultEventListener {
    @Override
    public int getEventTypes() {
        return Event.NODE_ADDED + Event.NODE_MOVED + Event.NODE_REMOVED + Event.PERSIST + Event.PROPERTY_ADDED + Event.PROPERTY_CHANGED + Event.PROPERTY_REMOVED;
    }

    @Override
    public void onEvent(EventIterator events) {
        while (events.hasNext()) {
            final Event event = events.nextEvent();
            try {
                final String userID = event.getUserID();
                JCRTemplate.getInstance().doExecuteWithSystemSession(userID, workspace, new JCRCallback<Object>() {
                    public Object doInJCR(JCRSessionWrapper session) throws RepositoryException {
                        final JCRItemWrapper item = session.getItem(event.getPath());
                        final Set<String> authorizedUsers = new HashSet<String>();
                        if (item.isNode()) {
                            Node node = (Node) item;
                            computeAuthorizedUsers(node, authorizedUsers);
                        }
                        else {
                            computeAuthorizedUsers(item.getParent(), authorizedUsers);
                        }
                        System.out.println("item = " + item);
                        System.out.println("authorizedUsers = " + authorizedUsers);
                        return item;
                    }

                    private void computeAuthorizedUsers(Node node, Set<String> authorizedUsers) throws RepositoryException {
                        if (node.hasNode("j:acl")) {
                            // Jahia specific ACL
                            Node aclNode = node.getNode("j:acl");
                            NodeIterator aces = aclNode.getNodes();
                            JahiaGroupManagerService groupService = ServicesRegistry.getInstance().getJahiaGroupManagerService();

                            while (aces.hasNext()) {
                                Node aceNode = aces.nextNode();
                                final String principal = aceNode.getProperty("j:principal").getString();
                                final boolean granted = aceNode.getProperty("j:aceType").getString().equals("GRANT");
                                if (granted) {
                                    Value[] roleValues = aceNode.getProperty("j:roles").getValues();
                                    for (Value role1 : roleValues) {
                                        String role = role1.getString();
                                        if("reader".equals(role)) {
                                            if(principal.startsWith("g:")) {
                                                final Set<Principal> members = groupService.lookupGroup(principal.substring(2)).getRecursiveUserMembers();
                                                for (Principal member : members) {
                                                    authorizedUsers.add(member.getName());
                                                }
                                            }
                                            else {
                                                // remove u: from principal
                                                authorizedUsers.add(principal.substring(2));
                                            }
                                        }
                                    }
                                }

                                // todo: deal with broken inheritancce
                                // acl.broken = aclNode.hasProperty("j:inherit") && !aclNode.getProperty("j:inherit").getBoolean();
                            }
                        }

                        // go up the chain to look for other users with access
                        final Node parent;
                        try {
                            parent = node.getParent();
                        } catch (ItemNotFoundException e) {
                            // we're at the root of the repository
                            return;
                        }
                        computeAuthorizedUsers(parent, authorizedUsers);
                    }
                });
            } catch (RepositoryException e) {
                e.printStackTrace();
            }
        }
    }
}
