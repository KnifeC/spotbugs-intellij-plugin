/*
 * Copyright 2020 SpotBugs plugin contributors
 *
 * This file is part of IntelliJ SpotBugs plugin.
 *
 * IntelliJ SpotBugs plugin is free software: you can redistribute it 
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation, either version 3 of 
 * the License, or (at your option) any later version.
 *
 * IntelliJ SpotBugs plugin is distributed in the hope that it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied 
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with IntelliJ SpotBugs plugin.
 * If not, see <http://www.gnu.org/licenses/>.
 */
package org.jetbrains.plugins.spotbugs.gui.settings;

import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.fileChooser.*;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.util.xmlb.SmartSerializer;
import org.jdom.Document;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.spotbugs.common.ExportErrorType;
import org.jetbrains.plugins.spotbugs.common.util.ErrorUtil;
import org.jetbrains.plugins.spotbugs.common.util.IdeaUtilImpl;
import org.jetbrains.plugins.spotbugs.common.util.IoUtil;
import org.jetbrains.plugins.spotbugs.core.ProjectSettings;
import org.jetbrains.plugins.spotbugs.core.WorkspaceSettings;
import org.jetbrains.plugins.spotbugs.resources.ResourcesLoader;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

final class AdvancedSettingsAction extends DefaultActionGroup {

	@NotNull
	private final SettingsPane settingsPane;

	@NotNull
	private final Project project;

	private boolean enabled;

	AdvancedSettingsAction(
			@NotNull final SettingsPane settingsPane,
			@NotNull final Project project,
			@Nullable final Module module
	) {
		super("Advanced Settings", true);
		this.settingsPane = settingsPane;
		this.project = project;
		this.enabled = true;
		getTemplatePresentation().setIcon(AllIcons.General.GearPlain);
		add(new ResetToDefault());
		add(new ImportSettings(module != null ? module.getName() : WorkspaceSettings.PROJECT_IMPORT_FILE_PATH_KEY));
		add(new ExportSettings());
	}

	void setEnabled(final boolean enabled) {
		this.enabled = enabled;
	}

	private class ResetToDefault extends AbstractAction {
		ResetToDefault() {
			super(
					StringUtil.capitalizeWords(ResourcesLoader.getString("settings.action.reset.title"), true),
					ResourcesLoader.getString("settings.action.reset.description"),
					AllIcons.Actions.Rollback
			);
		}

		@Override
		public void actionPerformed(@NotNull final AnActionEvent e) {
			final ProjectSettings settings = new ProjectSettings();
			settingsPane.reset(settings);
			settingsPane.resetProject(settings);
			settingsPane.resetWorkspace(new WorkspaceSettings());
		}
	}

	private class ImportSettings extends AbstractAction {
		@NotNull
		private final String importFilePathKey;

		ImportSettings(@NotNull final String importFilePathKey) {
			super(
					StringUtil.capitalizeWords(ResourcesLoader.getString("settings.action.import.title"), true),
					ResourcesLoader.getString("settings.action.import.description"),
					AllIcons.ToolbarDecorator.Import
			);
			this.importFilePathKey = importFilePathKey;
		}

		@Override
		public void actionPerformed(@NotNull final AnActionEvent e) {
			final Project project = IdeaUtilImpl.getProject(e.getDataContext());
			final FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor();
			descriptor.setTitle(ResourcesLoader.getString("settings.choose.title"));
			descriptor.setDescription(ResourcesLoader.getString("settings.choose.description"));
			descriptor.withFileFilter(
					virtualFile -> XmlFileType.DEFAULT_EXTENSION.equalsIgnoreCase(virtualFile.getExtension()));

			final VirtualFile file = FileChooser.chooseFile(descriptor, settingsPane, project, null);
			if (file != null) {
				try {
					final InputStream in = file.getInputStream();
					try {

						final ProjectSettings settings = new ProjectSettings();
						settingsPane.apply(settings);
						settingsPane.applyProject(settings);

						final boolean success = new SettingsImporter(project) {
							@Override
							protected void handleError(@NotNull final String title, @NotNull final String message) {
								Messages.showErrorDialog(message, title);
							}
						}.doImport(in, settings, importFilePathKey);

						if (success) {
							settingsPane.reset(settings);
							settingsPane.resetProject(settings);
							settingsPane.resetWorkspace(WorkspaceSettings.getInstance(project)); // support legacy
						}

					} finally {
						IoUtil.safeClose(in);
					}
				} catch (final Exception ex) {
					throw ErrorUtil.toUnchecked(ex);
				}
			}
		}
	}

	private class ExportSettings extends AbstractAction {
		ExportSettings() {
			super(
					StringUtil.capitalizeWords(ResourcesLoader.getString("settings.action.export.title"), true),
					ResourcesLoader.getString("settings.action.export.description"),
					AllIcons.ToolbarDecorator.Export
			);
		}

		@Override
		public void actionPerformed(@NotNull final AnActionEvent e) {

			final VirtualFileWrapper wrapper = FileChooserFactory.getInstance().createSaveFileDialog(
					new FileSaverDescriptor(
							ResourcesLoader.getString("settings.export.title"),
							ResourcesLoader.getString("settings.export.description"),
							XmlFileType.DEFAULT_EXTENSION
					), settingsPane).save(null, "SpotBugs");
			if (wrapper == null) {
				return;
			}

			final ProjectSettings settings = new ProjectSettings();
			try {
				settingsPane.apply(settings);
				settingsPane.applyProject(settings);
			} catch (final ConfigurationException ex) {
				Messages.showErrorDialog(settingsPane, ex.getMessage(), StringUtil.capitalizeWords(ResourcesLoader.getString("settings.invalid.title"), true));
			}

			Element root = new Element("findbugs");
			new SmartSerializer().writeExternal(settings, root, false);
			try {
				final File file = wrapper.getFile();
				final File exportDirPath = file.getAbsoluteFile().getParentFile();
				ExportErrorType errorType = ExportErrorType.from(exportDirPath);
				if (errorType != null) {
					Messages.showErrorDialog(settingsPane, errorType.getText(exportDirPath), StringUtil.capitalizeWords(ResourcesLoader.getString("settings.action.export.title"), true));
					actionPerformed(e);
					return;
				}
				JDOMUtil.writeDocument(new Document(root), file, "\n");
				RevealFileAction.showDialog(
						project,
						ResourcesLoader.getString("settings.export.success.text"),
						StringUtil.capitalizeWords(ResourcesLoader.getString("settings.export.success.title"), true),
						file,
						null
				);
			} catch (final IOException ex) {
				throw ErrorUtil.toUnchecked(ex);
			}
		}
	}

	private abstract class AbstractAction extends AnAction implements DumbAware {
		AbstractAction(@Nullable final String text, @Nullable final String description, @Nullable final Icon icon) {
			super(text, description, icon);
		}

		@Override
		public void update(@NotNull final AnActionEvent e) {
			e.getPresentation().setEnabled(enabled);
		}

		@Override
		public boolean isDumbAware() {
			return true;
		}
	}
}
