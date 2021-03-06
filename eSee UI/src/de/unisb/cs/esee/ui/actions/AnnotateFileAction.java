package de.unisb.cs.esee.ui.actions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.jface.text.revisions.Revision;
import org.eclipse.jface.text.revisions.RevisionInformation;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.ide.ResourceUtil;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditor;

import de.unisb.cs.esee.core.annotate.EseeAnnotations;
import de.unisb.cs.esee.core.data.RevisionInfo;
import de.unisb.cs.esee.core.data.SingleRevisionInfo;
import de.unisb.cs.esee.core.exception.BrokenConnectionException;
import de.unisb.cs.esee.core.exception.NotVersionedException;
import de.unisb.cs.esee.core.exception.UnsupportedSCMException;
import de.unisb.cs.esee.ui.ApplicationManager;
import de.unisb.cs.esee.ui.markers.RevMarker;
import de.unisb.cs.esee.ui.markers.RevisionAnnotation;
import de.unisb.cs.esee.ui.preferences.PreferenceConstants.HighlightingMode;
import de.unisb.cs.esee.ui.util.EseeUIUtil;
import de.unisb.cs.esee.ui.util.IRevisionHighlighter;
import de.unisb.cs.esee.ui.util.StdRevisionHighlighter;

public class AnnotateFileAction extends Thread {
    private final IFile file;
    private final boolean openEditorIfClosed;
    private final IProgressMonitor monitor;

    private static final QualifiedName MARKED_REV_VERSION_PROP = new QualifiedName(
	    "de.unisb.cs.esee.ui", "annotatedCacheVersion");
    private static final QualifiedName LAST_USED_MARKINGTYPE_PROP = new QualifiedName(
	    "de.unisb.cs.esee.ui", "lastMarkingType");

    private static final String CACHED_REVISION_INFORMATION_KEY = "de.unisb.cs.esee.ui.revInfoKey";

    public AnnotateFileAction(IFile file, boolean openEditorIfClosed,
	    IProgressMonitor monitor) {
	this.file = file;
	this.openEditorIfClosed = openEditorIfClosed;
	this.monitor = monitor;
    }

    @Override
    public void run() {
	try {
	    RevisionInfo revInfo = EseeAnnotations.getRevisionInfo(file,
		    monitor);

	    if (revInfo != null && file != null) {
		final HighlightingMode mode = ApplicationManager.getDefault()
			.getHighlightingMode();

		Object prop = file
			.getSessionProperty(AnnotateFileAction.MARKED_REV_VERSION_PROP);

		Long markedVersion = (Long) prop;
		RevisionInformation info = null;

		HighlightingMode lastUsedMode = (HighlightingMode) file
			.getSessionProperty(AnnotateFileAction.LAST_USED_MARKINGTYPE_PROP);
		final IRevisionHighlighter highlighter = new StdRevisionHighlighter();

		if (markedVersion == null
			|| revInfo.cacheVersionId != markedVersion.longValue()
			|| mode != lastUsedMode) {
		    final SingleRevisionInfo[] changes = revInfo.lines;

		    final RevisionInformation tmp_info = new RevisionInformation();
		    Map<String, RevisionAnnotation> revisions = new HashMap<String, RevisionAnnotation>();

		    BufferedReader content = null;

		    for (String mId : RevMarker.ID) {
			file.deleteMarkers(mId, false, IResource.DEPTH_ZERO);
		    }

		    file.deleteMarkers(RevMarker.ID_NEW_LINE, false,
			    IResource.DEPTH_ZERO);

		    if (Charset.isSupported(file.getCharset())) {
			content = new BufferedReader(new InputStreamReader(file
				.getContents(), Charset.forName(file
				.getCharset())));
		    } else {
			content = new BufferedReader(new InputStreamReader(file
				.getContents(), Charset.defaultCharset()));
		    }

		    int curCharPos = 0;

		    for (int line = 0; line < changes.length; ++line) {
			RevisionAnnotation revision = revisions
				.get(changes[line].revision);

			if (revision == null) {
			    revisions
				    .put(changes[line].revision,
					    revision = new RevisionAnnotation(
						    changes[line].revision,
						    changes[line].author,
						    new RGB(255, 0, 0),
						    changes[line].stamp));
			    tmp_info.addRevision(revision);
			}

			revision.addLine(line + 1);

			try {
			    changes[line].startPos = curCharPos;

			    int r;
			    while ((r = content.read()) != -1) {
				char c = (char) r;
				++curCharPos;

				if (c == System.getProperty("line.separator")
					.charAt(0)) {
				    break;
				}
			    }

			    changes[line].endPos = curCharPos;
			} catch (IOException e) {
			    e.printStackTrace();
			}
		    }

		    // finishing line range calculations in each revision
		    for (RevisionAnnotation revision : revisions.values()) {
			revision.addLine(RevisionAnnotation.END_LINE);
		    }

		    ResourcesPlugin.getWorkspace().run(
			    new IWorkspaceRunnable() {
				@SuppressWarnings("unchecked")
				public void run(IProgressMonitor monitor)
					throws CoreException {
				    switch (mode) {
				    case Unchecked:
					for (int line = 0; line < changes.length; ++line) {
					    try {
						Date changeDate = new Date(
							changes[line].stamp);
						if (highlighter
							.isChangeOfInterest(
								AnnotateFileAction.this.file,
								changeDate,
								changes[line].author)) {
						    IMarker m = file
							    .createMarker(RevMarker.ID_NEW_LINE);

						    m
							    .setAttributes(
								    new String[] {
									    IMarker.MESSAGE,
									    IMarker.CHAR_START,
									    IMarker.CHAR_END },
								    new Object[] {
									    "Changed on "
										    + changeDate
											    .toString()
										    + " by "
										    + changes[line].author,
									    changes[line].startPos,
									    changes[line].endPos - 1 });
						}
					    } catch (CoreException e) {
						e.printStackTrace();
					    }
					}

					break;
				    case Top5:
					TreeSet<Revision> revs = new TreeSet<Revision>(
						new Comparator<Revision>() {
						    public int compare(
							    Revision o1,
							    Revision o2) {
							return o2
								.getDate()
								.compareTo(
									o1
										.getDate());
						    }
						});
					revs.addAll(tmp_info.getRevisions());

					for (int line = 0; line < changes.length; ++line) {
					    try {
						int p = 0;
						for (Revision rev : revs) {
						    if (changes[line].revision
							    .equals(rev.getId())) {
							IMarker m = file
								.createMarker(RevMarker.ID[p]);

							m
								.setAttributes(
									new String[] {
										IMarker.MESSAGE,
										IMarker.CHAR_START,
										IMarker.CHAR_END },
									new Object[] {
										rev
											.getHoverInfo(),
										changes[line].startPos,
										changes[line].endPos - 1 });
						    }

						    if (++p == RevMarker.ID.length) {
							break;
						    }
						}
					    } catch (CoreException e) {
						e.printStackTrace();
					    }
					}

					break;
				    default:
					// ignore
				    }
				}
			    }, monitor);

		    info = tmp_info;

		    revInfo.setProperty(
			    AnnotateFileAction.CACHED_REVISION_INFORMATION_KEY,
			    info);
		    file
			    .setSessionProperty(
				    AnnotateFileAction.LAST_USED_MARKINGTYPE_PROP,
				    mode);
		    file.setSessionProperty(
			    AnnotateFileAction.MARKED_REV_VERSION_PROP,
			    new Long(revInfo.cacheVersionId));
		} else {
		    info = (RevisionInformation) revInfo
			    .getProperty(AnnotateFileAction.CACHED_REVISION_INFORMATION_KEY);
		}

		final RevisionInformation rinfo = info;
		PlatformUI.getWorkbench().getDisplay().syncExec(new Runnable() {
		    public void run() {
			try {
			    IWorkbenchPage page = EseeUIUtil.getActivePage();
			    IEditorPart editor = findEditor(page, file);

			    if (editor != null
				    && editor instanceof AbstractDecoratedTextEditor
				    && rinfo != null) {
				AbstractDecoratedTextEditor textEditor = (AbstractDecoratedTextEditor) editor;
				textEditor
					.showRevisionInformation(
						rinfo,
						EseeAnnotations
							.getResourceAnnotationsQuickDiffProvider(file));
			    }
			} catch (PartInitException e) {
			    e.printStackTrace();
			} catch (UnsupportedSCMException e) {
			    e.printStackTrace();
			} catch (BrokenConnectionException e) {
			    e.printStackTrace();
			} catch (NotVersionedException e) {
			    e.printStackTrace();
			}
		    }
		});

		monitor.done();
	    }
	} catch (Exception ex) {
	    // ignore this file
	}
    }

    protected IEditorPart findEditor(IWorkbenchPage page, IFile resource)
	    throws PartInitException {
	IEditorPart part = ResourceUtil.findEditor(page, resource);

	if (part != null && part instanceof AbstractDecoratedTextEditor) {
	    if (openEditorIfClosed) {
		page.activate(part);
	    }

	    return part;
	}

	return openEditorIfClosed ? IDE.openEditor(page, resource,
		EditorsUI.DEFAULT_TEXT_EDITOR_ID) : null;
    }
}
