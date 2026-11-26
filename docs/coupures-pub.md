# Coupures pub, chaîne par chaîne

Pour chaque chaîne, comment etincelle repère les coupures publicitaires, ce qu'il passe tout seul
selon le réglage, et où il reprend. État de la version 2026.11.44, au 26 novembre 2026.

## Le réglage « Passer les publicités automatiquement »

Une coupure qui n'est pas passée toute seule est toujours proposée par le bouton **Passer la pub**.

| Réglage | Passé tout seul |
|---|---|
| **Non** | rien : toujours le bouton |
| **Coupures signalées** | les coupures que la chaîne signale (groupes TF1 et France Télévisions) ; sur RMC et BFMTV, celles trouvées par la forme du flux ; les publicités que le serveur publicitaire place dans une coupure diffusée pendant qu'on regarde |
| **Signalées et presque sûres** | en plus, les coupures trouvées par les noirs entre les publicités, tout de suite, et celles trouvées par le logo et jugées sûres, 12 s après leur début. L'application prévient : peut couper la fin d'un générique ou quelques secondes d'émission. |

## Partout

- **Au direct, rien** : la fin de la coupure n'est pas encore diffusée. Il faut regarder en différé,
  d'au moins la durée de la coupure pour une coupure trouvée par le logo. La recherche par les noirs
  commence à 30 s du direct, celle par le logo à 60 s.
- **Regarder depuis le début** met en différé d'autant que le temps écoulé depuis le début du
  programme ; chaque coupure passée réduit ce retard.
- **Chromecast** : le récepteur ne passe rien tout seul ; il affiche **Passer la pub : OK** et la
  touche OK passe la coupure. Il trouve les coupures de la même façon, sauf par les publicités du
  serveur publicitaire, dont il n'a pas besoin.
- **Enregistrements** : coupures trouvées par les vignettes et les sous-titres, sûres seulement quand
  les deux sont d'accord ; reprise 32 s avant le retour du logo. Les replays n'ont pas de publicité.

## Les quatre façons de trouver une coupure

| Méthode | Comment | Reprise |
|---|---|---|
| **Signalée** | la chaîne marque chaque coupure dans le flux (SCTE-35) | exacte |
| **Forme RMC** | le flux est coupé en périodes : une partie, un jingle de moins de 10 s, une partie ; ou bien les publicités viennent du serveur publicitaire | exacte |
| **Noirs** | la demi-seconde de noir entre deux publicités, lue dans la taille des images du flux | à l'avant-dernier noir : la première publicité et une ou deux dernières passent |
| **Logo** | le logo de la chaîne disparaît des vignettes du service pendant la coupure | 32 s avant son retour, 24 s si la coupure est sûre |

## Chaîne par chaîne

Dans les tableaux, « Signalées » et « Presque sûres » désignent le réglage à partir duquel la coupure
est passée toute seule. Le retard est celui des vignettes du service sur le flux, mesuré le
26 novembre 2026.

### Chaînes qui signalent leurs coupures

Passées toutes seules dès **Coupures signalées**, reprise exacte.

| Chaîne | Trouvée par | Toute seule avec | Remarques |
|---|---|---|---|
| TF1, TMC, TFX, TF1 Séries Films | Signalée | Signalées | une période par coupure ; deux moitiés à 3 ou 4 s d'écart sont réunies |
| LCI | Signalée | Signalées | marques à l'intérieur d'une seule longue période |
| France 2, France 3, France 5 | Signalée | Signalées | |
| France 4, franceinfo, Arte | rien n'est cherché | | aucune coupure jamais vue |

### RMC et BFMTV

La forme du flux trouve environ un tiers des coupures RMC. Une coupure trouvée par la forme ou par le
serveur publicitaire compte comme signalée. Le logo prend le reste.

| Chaîne | Trouvée par | Toute seule avec | Retard | Remarques |
|---|---|---|---|---|
| RMC Story | Forme RMC, logo | Signalées ; Presque sûres pour un logo sûr | ≈ 6 s | les coupures de l'émission du matin sont d'une seule période, sans jingle : logo seulement |
| RMC Découverte | Forme RMC, logo | Signalées ; Presque sûres pour un logo sûr | non mesuré | entre deux épisodes, le logo est revenu 68 s après la reprise ; cette coupure n'était pas sûre : bouton seulement |
| RMC Life | Forme RMC, logo | Signalées ; Presque sûres pour un logo sûr | 7,4 s | |
| BFMTV | Forme RMC, logo | Signalées ; Presque sûres pour un logo sûr | ≈ 7,2 s | les longues coupures de la nuit, aux parties de 5 min 30 à 7 min, sont trouvées aussi |
| RMC Mécanic | logo | Presque sûres pour un logo sûr | non mesuré | ses périodes de 90 s sont des bandes-annonces de la chaîne, pas des coupures |

### Noirs entre les publicités, et logo

Une coupure trouvée par les noirs est passée tout de suite avec **Signalées et presque sûres**, du
deuxième noir à l'avant-dernier : la première et la dernière publicité passent toujours.
Un carton avec un seul noir ne coupe pas une coupure en deux, et une coupure
trouvée par le logo qui finit dans une coupure trouvée par les noirs va jusqu'au bout de celle-ci.

| Chaîne | Trouvée par | Toute seule avec | Retard | Remarques |
|---|---|---|---|---|
| M6 | noirs, logo | Presque sûres | 7,2 s | logo transparent, rarement sûr ; les cartons qui ouvrent et ferment les coupures aident |
| Gulli | noirs, logo | Presque sûres | 3,0 s | un dessin animé dont le logo va et vient n'est jamais sûr, à raison ; au changement de programme, le logo est revenu jusqu'à 136 s après la reprise : bouton seulement |
| Canal J | noirs, logo | Presque sûres | 2,6 s | |
| TIJI | noirs, logo | Presque sûres | 5,2 s | |
| Automoto | noirs, logo | Presque sûres | 4,7 s | |
| Toute l'Histoire | noirs, logo | Presque sûres | non mesuré | |
| AB 1 | noirs, logo | Presque sûres | 3,6 s | |
| Paris Première | noirs, logo | Presque sûres | 3,9 s | beaucoup de noirs manqués (première image sombre, noirs d'une seconde) ; une coupure coupée en deux par la marque d'une publicité dans un coin reste au bouton |

### Logo seulement

Une coupure jugée sûre est passée toute seule avec **Signalées et presque sûres**, 12 s après son
début. Les autres restent au bouton.

| Chaîne | Toute seule avec | Retard | Remarques |
|---|---|---|---|
| W9 | Presque sûres pour un logo sûr | non mesuré | ses cartons sont des barres animées : ils aident rarement à juger une coupure sûre |
| 6ter | Presque sûres pour un logo sûr | 3,4 s | |
| CSTAR | Presque sûres pour un logo sûr | ≈ 3,0 s | |
| CNEWS | Presque sûres pour un logo sûr | ≈ 2,6 s | une coupure sûre peut reprendre dans le générique d'ouverture de l'émission, qui ne compte pas comme l'émission |
| L'Équipe | Presque sûres pour un logo sûr | ≈ 3,8 s | |
| T18 | Presque sûres pour un logo sûr | 8,2 s | règle à part pour juger une coupure sûre : son logo s'en va juste avant la coupure |
| NOVO19 | Presque sûres pour un logo sûr | 2,7 s | |
| Téva | Presque sûres pour un logo sûr | 2,8 s | |
| toutes les autres | Presque sûres pour un logo sûr | non mesuré | logo par défaut, chaînes non étudiées |

## Où reprend une coupure trouvée par le logo

Elle reprend 32 s avant le retour du logo, 24 s quand elle est sûre. Les vignettes du service sont en
retard sur le flux, de 2,6 à 8,2 s selon la chaîne : il reste donc en pratique 27 à 40 s de
publicités avant l'émission (27 à 32 s pour une coupure sûre, 35 à 40 s pour les autres).

Cette marge couvre une émission qui reprend jusqu'à 20 s avant son logo, ce qui a été vérifié sur
210 coupures sûres en novembre 2026. Quand le logo revient bien plus tard, au changement de
programme, la coupure n'est pas sûre et reste au bouton.

## Limites connues

- Au direct, aucune coupure ne peut être passée.
- Sur Paris Première, beaucoup de coupures restent au bouton et reprennent 32 s avant l'émission.
- Au changement de programme, le logo peut revenir une minute ou plus après la reprise ; le bouton
  peut alors reprendre dans le programme suivant.
- L'émission du matin de RMC Story n'est trouvée que par le logo.
- Avec les noirs, la première et la dernière publicité d'une coupure passent toujours.
