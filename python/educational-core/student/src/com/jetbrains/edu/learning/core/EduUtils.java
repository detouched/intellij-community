package com.jetbrains.edu.learning.core;

import com.intellij.ide.SaveAndSyncHandler;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiDirectory;
import com.jetbrains.edu.learning.StudyUtils;
import com.jetbrains.edu.learning.courseFormat.*;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

public class EduUtils {
  private EduUtils() {
  }

  private static final Logger LOG = Logger.getInstance(EduUtils.class.getName());

  public static final Comparator<StudyItem> INDEX_COMPARATOR = (o1, o2) -> o1.getIndex() - o2.getIndex();

  public static void enableAction(@NotNull final AnActionEvent event, boolean isEnable) {
    final Presentation presentation = event.getPresentation();
    presentation.setVisible(isEnable);
    presentation.setEnabled(isEnable);
  }

  /**
   * Gets number index in directory names like "task1", "lesson2"
   *
   * @param fullName    full name of directory
   * @param logicalName part of name without index
   * @return index of object
   */
  public static int getIndex(@NotNull final String fullName, @NotNull final String logicalName) {
    if (!fullName.startsWith(logicalName)) {
      return -1;
    }
    try {
      return Integer.parseInt(fullName.substring(logicalName.length())) - 1;
    }
    catch (NumberFormatException e) {
      return -1;
    }
  }

  public static boolean indexIsValid(int index, Collection collection) {
    int size = collection.size();
    return index >= 0 && index < size;
  }

  @SuppressWarnings("IOResourceOpenedButNotSafelyClosed")
  @Nullable
  public static VirtualFile flushWindows(@NotNull final TaskFile taskFile, @NotNull final VirtualFile file) {
    final VirtualFile taskDir = file.getParent();
    VirtualFile fileWindows = null;
    final Document document = FileDocumentManager.getInstance().getDocument(file);
    if (document == null) {
      LOG.debug("Couldn't flush windows");
      return null;
    }
    if (taskDir != null) {
      final String name = file.getNameWithoutExtension() + EduNames.WINDOWS_POSTFIX;
      deleteWindowsFile(taskDir, name);
      PrintWriter printWriter = null;
      try {
        fileWindows = taskDir.createChildData(taskFile, name);
        printWriter = new PrintWriter(new FileOutputStream(fileWindows.getPath()));
        for (AnswerPlaceholder answerPlaceholder : taskFile.getAnswerPlaceholders()) {
          int length = answerPlaceholder.getRealLength();
          int start = answerPlaceholder.getOffset();
          final String windowDescription = document.getText(new TextRange(start, start + length));
          printWriter.println("#educational_plugin_window = " + windowDescription);
        }
        ApplicationManager.getApplication().runWriteAction(() -> FileDocumentManager.getInstance().saveDocument(document));
      }
      catch (IOException e) {
        LOG.error(e);
      }
      finally {
        if (printWriter != null) {
          printWriter.close();
        }
        synchronize();
      }
    }
    return fileWindows;
  }

  public static void synchronize() {
    FileDocumentManager.getInstance().saveAllDocuments();
    SaveAndSyncHandler.getInstance().refreshOpenFiles();
    VirtualFileManager.getInstance().refreshWithoutFileWatcher(true);
  }


  public static VirtualFile copyFile(Object requestor, VirtualFile toDir, VirtualFile file) {
    String name = file.getName();
    try {
      VirtualFile userFile = toDir.findChild(name);
      if (userFile != null) {
        userFile.delete(requestor);
      }
      return VfsUtilCore.copyFile(requestor, file, toDir);
    }
    catch (IOException e) {
      LOG.info("Failed to create file " + name + "  in folder " + toDir.getPath(), e);
    }
    return null;
  }

  @Nullable
  public static Pair<VirtualFile, TaskFile> createStudentFile(Object requestor,
                                                              Project project,
                                                              VirtualFile answerFile,
                                                              int stepIndex,
                                                              VirtualFile parentDir,
                                                              @Nullable Task task) {

    VirtualFile studentFile = copyFile(requestor, parentDir, answerFile);
    if (studentFile == null) {
      return null;
    }
    Document studentDocument = FileDocumentManager.getInstance().getDocument(studentFile);
    if (studentDocument == null) {
      return null;
    }
    if (task == null) {
      task = StudyUtils.getTaskForFile(project, answerFile);
      if (task == null) {
        return null;
      }
      task = task.copy();
    }
    Map<Integer, TaskFile> taskFileSteps = getTaskFileSteps(task, answerFile.getName());
    TaskFile initialTaskFile = taskFileSteps.get(-1);
    if (initialTaskFile == null) {
      return null;
    }
    EduDocumentListener listener = new EduDocumentListener(initialTaskFile, false);
    studentDocument.addDocumentListener(listener);

    Pair<VirtualFile, TaskFile> result = null;
    for (Map.Entry<Integer, TaskFile> entry : taskFileSteps.entrySet()) {
      Integer index = entry.getKey();
      if (index < stepIndex) {
        continue;
      }
      TaskFile stepTaskFile = entry.getValue();
      if (index == stepIndex) {
        result = Pair.createNonNull(studentFile, stepTaskFile);
      }
      for (AnswerPlaceholder placeholder : stepTaskFile.getAnswerPlaceholders()) {
        replaceAnswerPlaceholder(project, studentDocument, placeholder);
      }
    }
    studentDocument.removeDocumentListener(listener);
    return result;
  }

  public static Map<Integer, TaskFile> getTaskFileSteps(Task task, String name) {
    Map<Integer, TaskFile> files = new HashMap<>();
    files.put( -1, task.getTaskFile(name));
    List<Step> additionalSteps = task.getAdditionalSteps();
    if (!additionalSteps.isEmpty()) {
      for (int i = 0; i < additionalSteps.size(); i++) {
        files.put(i, additionalSteps.get(i).getTaskFiles().get(name));
      }
    }
    return files;
  }

  private static void replaceAnswerPlaceholder(@NotNull final Project project,
                                               @NotNull final Document document,
                                               @NotNull final AnswerPlaceholder answerPlaceholder) {
    final String taskText = answerPlaceholder.getTaskText();
    final int offset = answerPlaceholder.getOffset();
    CommandProcessor.getInstance().executeCommand(project, () -> ApplicationManager.getApplication().runWriteAction(() -> {
      document.replaceString(offset, offset + answerPlaceholder.getRealLength(), taskText);
      FileDocumentManager.getInstance().saveDocument(document);
    }), "Replace Answer Placeholders", "Replace Answer Placeholders");
  }

  public static void deleteWindowDescriptions(@NotNull final Task task, @NotNull final VirtualFile taskDir) {
    for (Map.Entry<String, TaskFile> entry : StudyUtils.getTaskFiles(task).entrySet()) {
      String name = entry.getKey();
      VirtualFile virtualFile = taskDir.findChild(name);
      if (virtualFile == null) {
        continue;
      }
      String windowsFileName = virtualFile.getNameWithoutExtension() + EduNames.WINDOWS_POSTFIX;
      deleteWindowsFile(taskDir, windowsFileName);
    }
  }

  private static void deleteWindowsFile(@NotNull final VirtualFile taskDir, @NotNull final String name) {
    final VirtualFile fileWindows = taskDir.findChild(name);
    if (fileWindows != null && fileWindows.exists()) {
      ApplicationManager.getApplication().runWriteAction(() -> {
        try {
          fileWindows.delete(taskDir);
        }
        catch (IOException e) {
          LOG.warn("Tried to delete non existed _windows file");
        }
      });
    }
  }

  @Nullable
  public static Task getTask(@NotNull final PsiDirectory directory, @NotNull final Course course) {
    PsiDirectory lessonDir = directory.getParent();
    if (lessonDir == null) {
      return null;
    }
    Lesson lesson = course.getLesson(lessonDir.getName());
    if (lesson == null) {
      return null;
    }
    return lesson.getTask(directory.getName());
  }

  public static boolean isImage(String fileName) {
    final String[] readerFormatNames = ImageIO.getReaderFormatNames();
    for (@NonNls String format : readerFormatNames) {
      final String ext = format.toLowerCase();
      if (fileName.endsWith(ext)) {
        return true;
      }
    }
    return false;
  }
}
