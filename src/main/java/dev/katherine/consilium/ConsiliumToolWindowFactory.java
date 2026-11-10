package dev.katherine.consilium;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class ConsiliumToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ConsiliumPanel panel = new ConsiliumPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel.component(), ConsiliumBundle.message("toolwindow.tab"), false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
