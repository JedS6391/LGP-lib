#!/bin/bash

branch_name=$(echo $1 | sed -e 's/\//-/g')
tag_name=$2
now=$(date +%Y-%m-%d)
version=$(./gradlew -q -Dorg.gradle.internal.launcher.welcomeMessageEnabled=false printVersion)
release_branch_regex="^release.*$"
feature_branch_regex="^feature.*$"

echo "$branch_name"
echo "$tag_name"

if [[ "$branch_name" =~ $feature_branch_regex ]]; then
    echo "Renaming build files for feature branch..."
    mv build/libs/LGP-lib-"$version".jar build/libs/LGP-lib-"$branch_name"-"$now".jar
    ls build/libs
elif [[ "$branch_name" =~ $release_branch_regex ]]; then
    echo "Renaming build files for release branch..."
    mv build/libs/LGP-lib-"$version".jar build/libs/LGP-lib-"$tag_name"-"$now".jar
    ls build/libs
else
    echo "Invalid deployment type"
fi