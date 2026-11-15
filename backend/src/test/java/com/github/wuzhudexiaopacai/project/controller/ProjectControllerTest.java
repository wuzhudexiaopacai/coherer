package com.github.wuzhudexiaopacai.project.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.wuzhudexiaopacai.audit.AuditLogService;
import com.github.wuzhudexiaopacai.common.GlobalExceptionHandler;
import com.github.wuzhudexiaopacai.project.service.ProjectService;
import com.github.wuzhudexiaopacai.project.vo.ProjectVO;
import com.github.wuzhudexiaopacai.security.DataScope;
import com.github.wuzhudexiaopacai.security.DataScopeService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProjectControllerTest {

    private ProjectService projectService;
    private MockMvc mvc;
    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        DataScopeService dataScopeService = mock(DataScopeService.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        when(dataScopeService.current()).thenReturn(DataScope.all());
        auth = new UsernamePasswordAuthenticationToken("admin", "n/a", List.of());
        mvc = MockMvcBuilders.standaloneSetup(
                        new ProjectController(projectService, dataScopeService, auditLogService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void list_returnsProjects() throws Exception {
        when(projectService.list(any(DataScope.class))).thenReturn(List.of(project()));

        mvc.perform(get("/api/projects").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("2"))
                .andExpect(jsonPath("$.data[0].name").value("项目 B"))
                .andExpect(jsonPath("$.data[0].spaceCount").value(3));
    }

    @Test
    void create_returnsProject() throws Exception {
        when(projectService.create(any())).thenReturn(project());

        mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"项目 B\",\"code\":\"prj-b\"}")
                        .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("2"))
                .andExpect(jsonPath("$.data.code").value("prj-b"));
    }

    @Test
    void create_blankName_isBadRequest() throws Exception {
        mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}")
                        .principal(auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void delete_returnsOk() throws Exception {
        mvc.perform(delete("/api/projects/2").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private ProjectVO project() {
        ProjectVO vo = new ProjectVO();
        vo.setId(2L);
        vo.setCode("prj-b");
        vo.setName("项目 B");
        vo.setStatus("ACTIVE");
        vo.setSpaceCount(3L);
        vo.setHostCount(1L);
        vo.setDeviceCount(5L);
        return vo;
    }
}
