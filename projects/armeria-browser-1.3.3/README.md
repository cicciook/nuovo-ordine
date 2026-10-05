# Armeria Browser 1.3.3

Hotfix per la modalità ANTEPRIMA mostrata dentro Minecraft.

- Il bridge JavaScript è presente direttamente nella pagina predefinita.
- BrowserBridge prepara e collega esplicitamente il bridge prima dell'apertura.
- La pagina 1.3.2 predefinita viene migrata automaticamente; viene lasciato un backup `shop.pre-1.3.3.bak`.
- Il catalogo automatico ritenta subito la scansione quando parte vuoto e poi ogni 10 secondi.
- Restano invariati acquisti server-authoritative, TNE/Vault e gestione OP.
