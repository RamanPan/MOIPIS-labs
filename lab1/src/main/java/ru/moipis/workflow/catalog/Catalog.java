package ru.moipis.workflow.catalog;

import ru.moipis.workflow.catalog.model.ObjectClassDefinition;
import ru.moipis.workflow.catalog.model.RoleDefinition;
import ru.moipis.workflow.catalog.model.UserDefinition;
import ru.moipis.workflow.template.model.ProcessTemplate;

import java.util.Optional;

public interface Catalog {

    Optional<ObjectClassDefinition> findClass(String classId);

    Optional<RoleDefinition> findRole(String roleId);

    Optional<UserDefinition> findUser(String userId);

    Optional<ProcessTemplate> findTemplate(String templateId);

    void saveClass(ObjectClassDefinition definition);

    void saveRole(RoleDefinition definition);

    void saveUser(UserDefinition definition);

    void saveTemplate(ProcessTemplate template);
}
