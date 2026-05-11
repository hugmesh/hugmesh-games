// Build entry for the producer-registration remote-shell.
// Side-effect-free; the host fetches the exposed component via
//   loadRemoteModule({remoteName:'producer-registration',
//                     exposedModule:'./ProducerRegistrationComponent'})
// per federation.config.js.
export { ProducerRegistrationRemoteComponent }
    from './app/producer-registration-remote.component';
