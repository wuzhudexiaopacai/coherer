package com.github.wuzhudexiaopacai.project.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.project.entity.Project;
import com.github.wuzhudexiaopacai.security.DataScope;
import com.github.wuzhudexiaopacai.security.DataScopeService;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectScopeServiceTest {

    private ProjectService projectService;
    private SpaceService spaceService;
    private DataScopeService dataScopeService;
    private ProjectScopeService service;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        spaceService = mock(SpaceService.class);
        dataScopeService = mock(DataScopeService.class);
        service = new ProjectScopeService(projectService, spaceService, dataScopeService);
    }

    @Test
    void noProject_delegatesToDataScope() {
        when(dataScopeService.resolveSpaceIds(any(), any())).thenReturn(List.of(10L));

        List<Long> result = service.resolveSpaceIds(
                DataScope.all(), null, null);

        assertEquals(List.of(10L), result);
    }

    @Test
    void project_unrestricted_returnsAllProjectSpaces() {
        when(projectService.requireVisible(2L, DataScope.all()))
                .thenReturn(project(2L));
        when(spaceService.spaceIdsOfProject(2L)).thenReturn(List.of(10L, 11L));

        List<Long> result = service.resolveSpaceIds(DataScope.all(), 2L, null);

        assertEquals(List.of(10L, 11L), result);
    }

    @Test
    void project_restricted_intersectsUserScope() {
        DataScope scope = DataScope.of(10L, List.of(10L, 11L));
        when(projectService.requireVisible(2L, scope)).thenReturn(project(2L));
        when(spaceService.spaceIdsOfProject(2L)).thenReturn(List.of(10L, 20L));

        List<Long> result = service.resolveSpaceIds(scope, 2L, null);

        assertEquals(List.of(10L), result);
    }

    @Test
    void spaceOutsideProject_isRejected() {
        DataScope scope = DataScope.of(10L, List.of(10L, 11L));
        when(projectService.requireVisible(2L, scope)).thenReturn(project(2L));
        when(spaceService.spaceIdsOfProject(2L)).thenReturn(List.of(10L));

        BizException exception = assertThrows(BizException.class,
                () -> service.resolveSpaceIds(scope, 2L, 11L));
        assertEquals(403, exception.getCode());
    }

    @Test
    void emptyProject_returnsEmptyList() {
        when(projectService.requireVisible(2L, DataScope.all())).thenReturn(project(2L));
        when(spaceService.spaceIdsOfProject(2L)).thenReturn(List.of());

        assertEquals(List.of(), service.resolveSpaceIds(DataScope.all(), 2L, null));
    }

    private Project project(Long id) {
        Project project = new Project();
        project.setId(id);
        project.setStatus("ACTIVE");
        return project;
    }
}
