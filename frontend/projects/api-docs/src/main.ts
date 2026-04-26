// Side-effect-free re-export entry. native-federation runs this inside
// the host shell; eager bootstrap or DOM mutation here would race the
// host's own bootstrapApplication.
export { DocsShellComponent }    from './app/docs-shell.component';
export { ApiExplorerComponent }  from './app/api-explorer.component';
export { ApiReferenceComponent } from './app/api-reference.component';
