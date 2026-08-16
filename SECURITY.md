# Sécurité

Ce document décrit honnêtement le modèle de sécurité de MCP Phone Use — ce qui est testé et confirmé, et ce qui reste une limite acceptée en connaissance de cause. Le code est public : la sécurité de ce projet repose sur la robustesse du modèle et le secret des clés, jamais sur le secret de la conception elle-même.

## Modèle de sécurité

**Double verrou** — aucun outil de contrôle (`get_ui_tree`, `device_action`, `get_screen`) ne répond sans les deux conditions réunies simultanément :
1. Une session OAuth 2.1/PKCE valide côté client MCP
2. L'app Android activement connectée côté téléphone (challenge-response récent, non expiré)

**Après l'enrôlement, aucun secret partagé n'est jamais retransmis.** Le serveur ne connaît que la clé *publique* de l'appareil ; chaque connexion prouve la possession de la clé privée (stockée dans l'Android Keystore, non exportable) en signant un nonce à usage unique.

**Comparaisons sensibles à temps constant** (mot de passe) — pas de fuite d'information par mesure de temps de réponse.

## Ce qui a été testé activement, pas juste supposé

- Résistance à une attaque de force brute réelle (tentatives rapides et répétées) sur les points d'entrée à secret devinable
- Configuration TLS : version, robustesse du chiffrement, validité et émetteur du certificat, refus des protocoles obsolètes, redirection HTTP → HTTPS
- Comportement face à des entrées volontairement malformées ou surdimensionnées (pas de fuite de trace d'erreur, pas de plantage exploitable)
- Résistance à un remplissage mémoire via les points d'entrée non authentifiés par nature (enregistrement de client OAuth)
- Distinction réelle entre déconnexion légère et révocation d'un appareil (testées séparément, comportements confirmés distincts)

## Limites connues, assumées

- **Conception mono-utilisateur** : un seul compte, pas d'authentification à plusieurs facteurs. Adapté à un usage personnel auto-hébergé, pas à un service partagé entre plusieurs personnes en l'état.
- **Pas de persistance** : l'état (appareils enrôlés, sessions) vit en mémoire — un redémarrage du relais efface tout. Contrepartie : c'est aussi, de fait, un mécanisme de révocation totale en cas de doute.
- **Un nouvel appareil enrôlé remplace silencieusement l'ancien**, sans notification ni journal dédié pour l'instant.
- **Signature de l'app** : les APK distribuées le sont en signature de débogage. Une signature de production reste à la charge de qui déploie sa propre instance (voir `CONTRIBUTING.md`).

## Signaler une vulnérabilité

Projet personnel, sans équipe de sécurité dédiée : ouvrez une issue sur le dépôt, ou contactez directement le mainteneur via son profil GitHub pour tout signalement sensible avant divulgation publique.
